package com.example.airemote

/**
 * A once-armed, one-action-per-verified-turn state machine.
 * It is not a recognizer or a strategy model: all evidence comes from separately
 * authenticated sources. It cannot authorize an Android gesture by itself.
 *
 * The operator arms a session once. A verified strategy may then make successive
 * decisions without another user tap. STOP, uncertainty and context changes are
 * terminal until a new explicit operator ARM.
 */
enum class AutoLoopState {
    STOPPED, WAITING_FOR_PROOF, WAITING_FOR_DECISION, WAITING_FOR_RESULT,
    CONFIRMED, BLOCKED_UNCERTAIN, BLOCKED_CONTEXT
}

data class AutonomousObservation(
    val view: VerifiedLiveView,
    val tableId: String,
    val handId: String,
    val turnId: String,
    val foregroundPackage: String,
    val windowGeneration: Long,
    val tableHandIndependentlyVerified: Boolean,
    val windowIndependentlyVerified: Boolean,
    val phase: HandStage,
    val timerRemainingMs: Long?,
    val potChips: Long?,
    val callChips: Long?,
    val availableTaps: Map<LiveAction, VerifiedTapArea>,
    /** For BET/RAISE, the amount must already be visibly selected and independently checked. */
    val selectedBetChips: Long? = null,
    /** Independent evaluator binds equity to this exact frame and turn. */
    val equityEvidence: FrameBoundEquity? = null,
    /** Strict pre-verified raise range; unknown bounds prohibit BET/RAISE. */
    val minVerifiedBetChips: Long? = null,
    val maxVerifiedBetChips: Long? = null
)

data class AutonomousDecision(
    val action: LiveAction,
    val basedOnTurnId: String,
    val reason: String,
    /** Required for BET/RAISE; never inferred from a default slider position. */
    val amountChips: Long? = null
)

fun interface AutonomousStrategy {
    fun decide(observation: AutonomousObservation): AutonomousDecision?
}

/** In production the adapter delegates to LiveInputCoordinator and LiveGameActionGuard. */
interface AutonomousTransport {
    fun submit(id: String, action: LiveAction, view: VerifiedLiveView,
               tap: VerifiedTapArea, nowMs: Long): LiveCommandStatus
    fun observe(view: VerifiedLiveView, nowMs: Long): LiveCommandStatus
    fun stop()
}

data class AutonomousLoopResult(val state: AutoLoopState, val explanation: String,
                                val commandId: String? = null)

class AutonomousActionLoop(
    private val strategy: AutonomousStrategy,
    private val transport: AutonomousTransport,
    private val requiredRemainingMs: Long = 1600L,
    private val maximumFrameAgeMs: Long = 1200L,
    private val diagnosticSink: (IncidentEvent) -> Unit = {},
    private val liveClockMs: (() -> Long)? = null
) {
    private fun log(code: IncidentCode, severity: IncidentSeverity = IncidentSeverity.INFO,
                    view: VerifiedLiveView? = null, action: LiveAction? = null,
                    nowMs: Long = 0L, obs: AutonomousObservation? = null) {
        try { diagnosticSink(IncidentEvent(nowMs, severity, code,
            view?.captureEpoch, view?.frameSequence,
            if (view != null) (nowMs - view.capturedAtMs).coerceAtLeast(0L) else null,
            action?.name, obs?.phase?.name, obs?.potChips, obs?.callChips,
            obs?.timerRemainingMs, obs?.view?.legalActions?.map { it.name }?.toSet()
                ?: emptySet())) } catch (_: Exception) { /* Never interfere with STOP. */ }
    }
    private data class ContextKey(val packageName: String, val table: String, val hand: String,
                                  val epoch: Long, val generation: Long)
    @Volatile private var abortRequested = true
    private var armed = false
    private var context: ContextKey? = null
    private var commandCounter = 0L
    private var pendingTurn: String? = null
    private var pendingCommand: String? = null
    // Never dispatch twice for one turn even after Android or game acknowledgement.
    private val usedTurns = mutableSetOf<String>()
    private var current = AutonomousLoopResult(AutoLoopState.STOPPED, "Автоматизация выключена")

    @Synchronized fun status(): AutonomousLoopResult = current

    /** Lock-free kill signal: works even while a remote strategy is still computing.
     * Gesture permission is revoked separately by OperatorStopStore before saving STOP.
     */
    fun requestAbort() {
        abortRequested = true
        try { transport.stop() } catch (_: Exception) { }
    }

    /** Must only be called after the durable operator ARM and external permission checks. */
    @Synchronized fun arm(operatorConsented: Boolean, externalAutomationAllowed: Boolean): Boolean {
        if (!operatorConsented || !externalAutomationAllowed || armed) return false
        context = null
        usedTurns.clear()
        pendingTurn = null
        pendingCommand = null
        armed = true
        abortRequested = false
        log(IncidentCode.SESSION_ARMED)
        current = AutonomousLoopResult(AutoLoopState.WAITING_FOR_PROOF,
            "Ожидание независимых подтверждений игры, стола, раздачи и хода")
        return true
    }

    @Synchronized fun stop(reason: String = "STOP оператора"): AutonomousLoopResult {
        abortRequested = true
        armed = false
        context = null
        usedTurns.clear()
        pendingTurn = null
        pendingCommand = null
        transport.stop()
        log(IncidentCode.OPERATOR_STOP)
        current = AutonomousLoopResult(AutoLoopState.STOPPED, reason)
        return current
    }

    private fun block(reason: String, uncertain: Boolean = false): AutonomousLoopResult {
        abortRequested = true
        armed = false
        log(IncidentCode.SAFETY_BLOCKED, IncidentSeverity.ERROR)
        transport.stop()
        pendingTurn = null
        pendingCommand = null
        current = AutonomousLoopResult(
            if (uncertain) AutoLoopState.BLOCKED_UNCERTAIN else AutoLoopState.BLOCKED_CONTEXT,
            "$reason. Нужна проверка оператора и повторный ARM")
        return current
    }

    @Synchronized fun onVerifiedObservation(
        obs: AutonomousObservation?, nowMs: Long,
        operatorStopped: Boolean, captureActive: Boolean,
        externalAutomationAllowed: Boolean
    ): AutonomousLoopResult {
        if (operatorStopped || !externalAutomationAllowed || !captureActive) return stop(
            "STOP, отсутствие захвата или отозванное разрешение")
        if (abortRequested || !armed) return current
        if (obs == null) {
            if (pendingCommand != null) return block("После действия потеряно наблюдение", true)
            current = AutonomousLoopResult(AutoLoopState.WAITING_FOR_PROOF,
                "Нет проверенного состояния стола")
            return current
        }
        val view = obs.view
        val provenanceValid = obs.tableHandIndependentlyVerified &&
            obs.windowIndependentlyVerified &&
            obs.foregroundPackage.isNotBlank() &&
            obs.tableId.isNotBlank() && obs.handId.isNotBlank() &&
            obs.windowGeneration >= 0L && view.captureEpoch >= 0L &&
            view.frameSequence > 0L && view.handKey == obs.handId &&
            view.foregroundVerified && view.stageVerified &&
            view.capturedAtMs in (nowMs - maximumFrameAgeMs)..nowMs
        if (!provenanceValid) {
            if (pendingCommand != null) return block("Потеряна проверенная игровая информация", true)
            current = AutonomousLoopResult(AutoLoopState.WAITING_FOR_PROOF,
                "Нет независимых, свежих и согласованных данных")
            return current
        }
        val key = ContextKey(obs.foregroundPackage, obs.tableId, obs.handId,
            view.captureEpoch, obs.windowGeneration)
        if (context != null && context != key) {
            // NEVER move to another hand/window while an action could still complete.
            if (pendingCommand != null) return block("Контекст сменился до подтверждения действия", true)
            log(IncidentCode.CONTEXT_CHANGED, IncidentSeverity.WARNING, view, nowMs = nowMs)
            context = key
            usedTurns.clear()
        }
        if (context == null) context = key
        if (pendingCommand != null) {
            val result = transport.observe(view, nowMs)
            if (result == LiveCommandStatus.CONFIRMED) {
                log(IncidentCode.COMMAND_CONFIRMED, view = view, nowMs = nowMs)
                pendingTurn = null
                pendingCommand = null
                current = AutonomousLoopResult(AutoLoopState.CONFIRMED,
                    "Изменение игрового состояния подтверждено новым наблюдением")
            } else if (result == LiveCommandStatus.UNCERTAIN || result == LiveCommandStatus.REFUSED) {
                log(IncidentCode.COMMAND_UNCERTAIN, IncidentSeverity.ERROR, view, nowMs = nowMs)
                return block("Не удалось подтвердить результат касания", true)
            } else current = AutonomousLoopResult(AutoLoopState.WAITING_FOR_RESULT,
                "Ожидание проверенного изменения, без повторного касания", pendingCommand)
            return current
        }
        val readyToAct = obs.turnId.isNotBlank() && view.turnVerified && view.exactLabelsVerified &&
            obs.phase in setOf(HandStage.PREFLOP, HandStage.FLOP, HandStage.TURN, HandStage.RIVER) &&
            obs.timerRemainingMs != null && obs.timerRemainingMs >= requiredRemainingMs &&
            obs.potChips != null && obs.potChips >= 0L &&
            obs.callChips != null && obs.callChips >= 0L
        if (!readyToAct) {
            current = AutonomousLoopResult(AutoLoopState.WAITING_FOR_PROOF,
                "Ход, таймер, банк или точные кнопки не подтверждены")
            return current
        }
        val turnUnique = "${key.packageName}|${key.table}|${key.hand}|${key.epoch}|${key.generation}|${obs.turnId}"
        if (turnUnique in usedTurns) {
            current = AutonomousLoopResult(AutoLoopState.WAITING_FOR_DECISION,
                "Этот ход уже обработан; ждём нового подтверждённого хода")
            return current
        }
        // The strategy (including a remote model) may respond after the turn expired.
        // Check again AFTER it returns and BEFORE touching the Android transport.
        val decision = try { strategy.decide(obs) } catch (_: Exception) {
            log(IncidentCode.DECISION_MISSING, IncidentSeverity.ERROR, view, nowMs = nowMs, obs = obs)
            return block("Стратегия завершилась ошибкой; действие запрещено", true)
        }
        if (abortRequested) return stop("STOP получен во время расчёта стратегии")
        val updatedNow = liveClockMs?.invoke() ?: nowMs
        if (DecisionDeadlineGate.assess(view.capturedAtMs, nowMs, updatedNow,
                obs.timerRemainingMs, requiredRemainingMs, maximumFrameAgeMs) !=
                DeadlineResult.SAFE_TO_CONTINUE) {
            log(IncidentCode.DECISION_EXPIRED, IncidentSeverity.ERROR, view, decision?.action,
                updatedNow, obs)
            return block("Ответ стратегии опоздал; время хода или кадр устарели", true)
        }
        if (decision == null) {
            log(IncidentCode.DECISION_MISSING, IncidentSeverity.WARNING, view, nowMs = nowMs, obs = obs)
            current = AutonomousLoopResult(AutoLoopState.WAITING_FOR_DECISION,
                "Стратегия не выдала решение; действие запрещено")
            return current
        }
        val tap = obs.availableTaps[decision.action]
        val amountSafe = decision.action !in setOf(LiveAction.BET, LiveAction.RAISE) ||
            (decision.amountChips != null && decision.amountChips > 0 &&
                decision.amountChips == obs.selectedBetChips)
        if (decision.basedOnTurnId != obs.turnId || !amountSafe ||
            decision.action !in view.legalActions || tap == null ||
            tap.exactAction != decision.action ||
            tap.foregroundPackage != obs.foregroundPackage ||
            LiveTapSafety.verify(tap, LiveCommand("validation", obs.handId, decision.action,
                    view.captureEpoch, view.frameSequence, view.stateFingerprint, updatedNow,
                    view.capturedAtMs), obs.foregroundPackage, updatedNow, updatedNow,
                tap.screenWidth, tap.screenHeight, obs.foregroundPackage) != TapSafetyResult.VALID) {
            log(IncidentCode.DECISION_REJECTED, IncidentSeverity.ERROR, view,
                decision.action, updatedNow, obs)
            current = AutonomousLoopResult(AutoLoopState.WAITING_FOR_PROOF,
                "Стратегия предложила неподтверждённое действие или сумму")
            return current
        }
        if (abortRequested) return stop("STOP до отправки команды")
        commandCounter++
        val commandId = "auto-${view.captureEpoch}-${view.frameSequence}-$commandCounter"
        // Remember before submission: a timeout or failed callback must never cause an automatic retry.
        usedTurns.add(turnUnique)
        val submitted = transport.submit(commandId, decision.action, view, tap, updatedNow)
        if (submitted != LiveCommandStatus.READY_TO_DISPATCH &&
            submitted != LiveCommandStatus.AWAITING_CONFIRMATION) {
            return block("Android отклонил команду")
        }
        log(IncidentCode.COMMAND_REQUESTED, view = view, action = decision.action,
            nowMs = updatedNow, obs = obs)
        pendingTurn = obs.turnId
        pendingCommand = commandId
        current = AutonomousLoopResult(AutoLoopState.WAITING_FOR_RESULT,
            "Команда передана Android; подтверждение по новому кадру обязательно", commandId)
        return current
    }
}
