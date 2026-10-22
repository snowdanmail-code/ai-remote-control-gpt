package com.example.airemote

import android.content.Context
import android.os.SystemClock

/** Android adapter: the only transport is the existing verified Accessibility dispatcher. */
private class VerifiedAndroidTransport(private val context: Context) : AutonomousTransport {
    override fun submit(id: String, action: LiveAction, view: VerifiedLiveView,
                        tap: VerifiedTapArea, nowMs: Long): LiveCommandStatus =
        LiveInputCoordinator.requestVerifiedAction(context, id, action, view, tap, nowMs) { accepted ->
            // Submission to the OS is NOT a confirmed game action.
            // A failure is terminal, never a reason to blindly send another tap.
            if (!accepted) OnDeviceAutonomousRuntime.stop("Android отклонил жест")
        }
    override fun observe(view: VerifiedLiveView, nowMs: Long): LiveCommandStatus =
        OnDeviceLiveActionControl.guard.observe(view, nowMs)
    override fun stop() { OnDeviceLiveActionControl.stop() }
}

/**
 * Installed strategy can be replaced only while stopped. There is no built-in
 * ChatGPT credential, and no assumption that ChatGPT Plus enables API access.
 * No producer of independently verified table/hand observations is currently connected.
 */
object OnDeviceAutonomousRuntime {
    @Volatile private var controller: AutonomousActionLoop? = null
    private val stopGeneration = java.util.concurrent.atomic.AtomicLong(0L)
    private var strategy: AutonomousStrategy? = null
    @Volatile private var lastStatus = AutonomousLoopResult(AutoLoopState.STOPPED, "AUTO выключен")
    @Volatile private var lastDiagnostic: String = "Фоновое OCR ещё не поступало"
    // Independent, read-only turn lifecycle: diagnostic visibility is not a legal move.
    private val turnTimeline = VerifiedTurnLifecycle()
    private var identityBinding: VerifiedTurnBinding? = null

    /** No production verifier is installed until a genuinely independent provider exists. */
    @Synchronized fun installExternalIdentityVerifier(verifier: ExternalAttestationVerifier) {
        controller?.stop("Смена проверяющего источника")
        controller = null
        identityBinding = VerifiedTurnBinding(verifier)
        turnTimeline.reset("Смена проверяющего источника")
    }
    @Volatile private var latestTurnCycle = TurnCycleReport(
        TurnCyclePhase.UNKNOWN, 0L, -1L, "Ждём подтверждённых кадров")

    @Synchronized fun installStrategy(verifiedSource: AutonomousStrategy) {
        controller?.stop("Смена стратегии")
        controller = null
        turnTimeline.reset("Смена стратегии")
        identityBinding?.reset()
        OnDeviceVerifiedDispatchAdmission.clear()
        latestTurnCycle = turnTimeline.snapshot(SystemClock.elapsedRealtime())
        strategy = verifiedSource
        lastStatus = AutonomousLoopResult(AutoLoopState.STOPPED,
            "Стратегия подключена; ожидается ARM и проверенный источник игровых данных")
    }

    /** Install local AI once; it never disables external permission/STOP checks. */
    @Synchronized fun ensureLocalStrategy() {
        if (strategy == null && controller == null) strategy = VerifiedLocalPokerStrategy()
    }

    @Synchronized fun startIfAuthorized(context: Context): Boolean {
        val generationAtStart = stopGeneration.get()
        if (CaptureObservationMode.isSpectator() ||
            OperatorStopStore.isStopped(context) || !FrameStore.isCapturing ||
            !LiveGamePermissionGate.permitted() || !LiveForegroundProbe.connected() ||
            strategy == null) return false
        val app = context.applicationContext
        val loop = AutonomousActionLoop(strategy!!, VerifiedAndroidTransport(app),
            diagnosticSink = { e ->
                OnDeviceIncidentRecorder.record(app, e.code, e.severity,
                    e.captureEpoch, e.frameSequence, e.latencyMs, e.action,
                    e.stage, e.potChips, e.callChips, e.timerRemainingMs, e.legalActions)
            },
            liveClockMs = { SystemClock.elapsedRealtime() })
        if (!loop.arm(operatorConsented = true, externalAutomationAllowed = true)) return false
        turnTimeline.reset("Новый автономный сеанс")
        identityBinding?.reset()
        OnDeviceVerifiedDispatchAdmission.clear()
        latestTurnCycle = turnTimeline.snapshot(SystemClock.elapsedRealtime())
        controller = loop
        if (stopGeneration.get() != generationAtStart ||
            OperatorStopStore.isStopped(context) || !LiveGamePermissionGate.permitted()) {
            loop.requestAbort()
            if (controller === loop) controller = null
            lastStatus = AutonomousLoopResult(AutoLoopState.STOPPED, "STOP во время запуска")
            return false
        }
        lastStatus = loop.status()
        return true
    }

    /**
     * Capture/OCR stream is now connected to the controller in read-only mode.
     * An OCR result has NO authenticated table, hand or turn identity, so the loop
     * receives null, never a fabricated verified observation. Future authenticated
     * producers must use acceptVerifiedObservation separately.
     */
    @Synchronized fun acceptAutomaticDiagnostic(context: Context, evidence: AutomaticOcrEvidence) {
        lastDiagnostic = evidence.summary()
        // IMPORTANT: this is only a visual hint. Forwarding null to the action loop
        // could falsely fail an already dispatched command awaiting game confirmation.
        // Only acceptVerifiedObservation can confirm or refute a live action.
    }

    /** Called only by an independently authenticated, frame-bound game state producer. */
    @Synchronized fun acceptVerifiedObservation(context: Context, obs: AutonomousObservation?,
                                                sourceProof: ExternalTurnAttestation? = null): AutonomousLoopResult {
        if (CaptureObservationMode.isSpectator()) {
            stop("Режим наблюдателя: любые игровые действия запрещены")
            return lastStatus
        }
        val loop = controller ?: return lastStatus
        val frame = FrameStore.latest
        val origin = FrameStore.windowOrigin
        val foreground = LiveForegroundProbe.snapshot()
        val now = SystemClock.elapsedRealtime()
        val tiedToCurrent = obs != null && frame != null && origin != null && foreground != null &&
            origin.safeForLocalDiagnostics && obs.view.captureEpoch == frame.captureEpoch &&
            obs.view.frameSequence == frame.seq && obs.view.capturedAtMs == frame.capturedAtMs &&
            obs.foregroundPackage == frame.sourcePackage &&
            obs.windowGeneration == frame.windowGeneration &&
            obs.windowGeneration == origin.windowGeneration &&
            obs.windowGeneration == foreground.windowGeneration &&
            foreground.packageName == obs.foregroundPackage &&
            obs.foregroundPackage == LiveGamePermissionGate.packageName() &&
            obs.view.handKey == obs.handId &&
            obs.availableTaps.values.all { it.foregroundPackage == obs.foregroundPackage &&
                it.windowGeneration == obs.windowGeneration }
        // The following observations must agree on the SAME JPEG and hand source.
        // A stable OCR label does not itself verify the identity of a player's turn.
        val turnVisual = FrameStore.confirmedTurnVisual
        val actionStrip = FrameStore.lastActionStrip
        val stableControlStrip = tiedToCurrent && obs != null && actionStrip != null &&
            actionStrip.status == ActionStripStatus.STABLE_DIAGNOSTIC &&
            actionStrip.frameSequence == obs.view.frameSequence &&
            actionStrip.stable.isNotEmpty() &&
            obs.view.legalActions.isNotEmpty() &&
            obs.view.legalActions.all { action ->
                actionStrip.stable.any { it.action.name == action.name }
            }
        val visualState = if (turnVisual != null && obs != null &&
            turnVisual.captureEpoch == obs.view.captureEpoch &&
            turnVisual.sourceVerified &&
            turnVisual.capturedAtMs <= obs.view.capturedAtMs &&
            obs.view.capturedAtMs - turnVisual.capturedAtMs <= 1_500L
        ) turnVisual.state else TurnDisplayState.UNKNOWN
        if (obs != null && tiedToCurrent) {
            latestTurnCycle = turnTimeline.observe(TurnCycleFrame(
                tableId = obs.tableId, handId = obs.handId,
                independentlyVerifiedIdentity = obs.tableHandIndependentlyVerified,
                foregroundVerified = obs.windowIndependentlyVerified && obs.view.foregroundVerified,
                captureEpoch = obs.view.captureEpoch,
                windowGeneration = obs.windowGeneration,
                frameSequence = obs.view.frameSequence,
                capturedAtMs = obs.view.capturedAtMs,
                inspectedAtMs = now,
                stopped = OperatorStopStore.isStopped(context),
                visual = visualState,
                actionLabelsStable = stableControlStrip,
                actionLabelsConsistent = actionStrip?.status != ActionStripStatus.REJECTED
            ))
        }
        val sourceBinding = identityBinding?.inspect(sourceProof, obs?.view, foreground?.packageName,
            foreground?.windowGeneration, latestTurnCycle, now, OperatorStopStore.isStopped(context))
        val independentlyBound = sourceBinding?.status == TurnBindingStatus.CONSISTENT_READ_ONLY &&
            obs != null && sourceBinding.handId == obs.handId &&
            sourceBinding.tableId == obs.tableId && sourceBinding.turnId == obs.turnId
        // Observations during WAITING_FOR_RESULT verify effects after a gesture;
        // buttons may already be gone, so they do not require a fresh READY strip.
        val awaitingEffect = loop.status().state == AutoLoopState.WAITING_FOR_RESULT
        val permittedByTurn = awaitingEffect ||
            (latestTurnCycle.phase == TurnCyclePhase.VISIBLE_DIAGNOSTIC &&
                stableControlStrip && obs?.view?.turnVerified == true &&
                obs.view.exactLabelsVerified)
        if (obs != null && (!tiedToCurrent || !permittedByTurn)) {
            OnDeviceIncidentRecorder.record(context, IncidentCode.OBSERVATION_UNVERIFIED,
                IncidentSeverity.WARNING, obs.view.captureEpoch, obs.view.frameSequence)
        }
        // Visual flags supplied by the caller never substitute for attested source proof.
        val readyForNewDecision = loop.status().state != AutoLoopState.WAITING_FOR_RESULT
        OnDeviceVerifiedDispatchAdmission.clear()
        if (readyForNewDecision && tiedToCurrent && independentlyBound && permittedByTurn && obs != null) {
            OnDeviceVerifiedDispatchAdmission.gate.admit(obs.view, obs.foregroundPackage,
                obs.windowGeneration, now, trustedExternalBinding = true,
                stopped = OperatorStopStore.isStopped(context))
        }
        val result = loop.onVerifiedObservation(if (tiedToCurrent && independentlyBound && permittedByTurn) obs else null, now,
            operatorStopped = OperatorStopStore.isStopped(context),
            captureActive = FrameStore.isCapturing,
            externalAutomationAllowed = LiveGamePermissionGate.permitted())
        // STOP can occur without waiting for this synchronized method to finish.
        if (controller === loop) lastStatus = result
        return lastStatus
    }

    /** Nonblocking emergency cancellation: do not wait for a slow strategy. */
    fun stop(reason: String = "STOP"): AutonomousLoopResult {
        stopGeneration.incrementAndGet()
        // The authorization is valid for ONE capture/autonomy session only;
        // do not reuse it after a crash, MediaProjection stop or interruption.
        LiveGamePermissionGate.revoke()
        OnDeviceVerifiedDispatchAdmission.clear()
        OnDeviceEffectAuthority.clear()
        lastDiagnostic = "Данные очищены после STOP/завершения захвата"
        turnTimeline.reset(reason)
        identityBinding?.reset()
        latestTurnCycle = TurnCycleReport(TurnCyclePhase.UNKNOWN, 0L, -1L, reason)
        controller?.requestAbort()
        controller = null
        lastStatus = AutonomousLoopResult(AutoLoopState.STOPPED, reason)
        return lastStatus
    }

    fun verifiedSourceConfigured(): Boolean = identityBinding != null
    fun strategyInstalled(): Boolean = strategy != null

    fun readiness(context: Context): String = when {
        CaptureObservationMode.isSpectator() -> "Режим наблюдателя: игровые касания отключены"
        OperatorStopStore.isStopped(context) -> "Включён STOP: нужен ARM оператора"
        !FrameStore.isCapturing -> "Нет действующего захвата MediaProjection"
        !LiveForegroundProbe.connected() -> "AccessibilityService не подключена"
        !LiveGamePermissionGate.permitted() -> "Разрешите касания для недавно открытого 1win в панели оператора"
        strategy == null -> "Профессиональная стратегия/ChatGPT не подключена"
        else -> "Касания разрешены; автоцикл ждёт независимое подтверждение стола, раздачи, хода и точных кнопок"
    }

    fun status(): AutonomousLoopResult = lastStatus
    fun turnCycleStatus(): TurnCycleReport = latestTurnCycle
    fun diagnostic(): String = lastDiagnostic
}
