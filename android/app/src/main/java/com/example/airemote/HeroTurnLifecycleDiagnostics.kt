package com.example.airemote

/**
 * Read-only detection of visual hero-turn boundaries in the ORIGINAL captured screen.
 * A stable action strip is evidence of a screen transition, NOT independent evidence
 * of the game, the hand, legal actions, or authorization to send Android gestures.
 */
enum class HeroTurnPhase {
    NO_BASELINE, WAITING_VISUAL, TURN_APPEARED_VISUAL, ACTIVE_VISUAL,
    SIZING_VISUAL, TURN_DISAPPEARED_VISUAL, COLLECTING, REJECTED
}

data class HeroTurnLifecycleReport(
    val phase: HeroTurnPhase,
    val reason: String,
    val captureEpoch: Long = -1L,
    val frameSequence: Long = -1L,
    val windowGeneration: Long = -1L,
    /** Local, ephemeral counter; NEVER a table/hand/turn identifier. */
    val localVisualEpisode: Long = 0L
) {
    val authorizesTap: Boolean get() = false
    val confirmsGameTurn: Boolean get() = false
    fun summary(): String = "Визуальный ход: $phase; эпизод $localVisualEpisode. " +
        "Не подтверждает раздачу и не разрешает касания. $reason"
}

class HeroTurnLifecycleDiagnostics(
    private val maximumFrameAgeMs: Long = 1_700L,
    private val maximumGapMs: Long = 3_000L,
    private val consecutiveSamples: Int = 2
) {
    private data class Context(val epoch: Long, val pkg: String, val generation: Long)
    private var context: Context? = null
    private var lastFrame = -1L
    private var lastAt = -1L
    private var candidate: TurnDisplayState? = null
    private var samples = 0
    private var stable: TurnDisplayState? = null
    private var anchored = false
    private var active = false
    private var episode = 0L

    @Synchronized fun reset() {
        context = null
        lastFrame = -1L
        lastAt = -1L
        candidate = null
        samples = 0
        stable = null
        anchored = false
        active = false
        episode = 0L
    }

    @Synchronized fun inspect(
        signal: TurnVisualSignal,
        foregroundPackage: String?, windowGeneration: Long?,
        originSafe: Boolean, nowMs: Long, stopped: Boolean
    ): HeroTurnLifecycleReport {
        fun rejected(reason: String): HeroTurnLifecycleReport {
            reset()
            return HeroTurnLifecycleReport(HeroTurnPhase.REJECTED, reason)
        }
        val reading = signal.reading
        val rolesOk = when (reading.state) {
            TurnDisplayState.READY -> VisibleButtonRole.RED_FOLD in reading.roles &&
                VisibleButtonRole.GREEN_CHECK_OR_CALL in reading.roles
            TurnDisplayState.BET_SIZING -> reading.roles == setOf(VisibleButtonRole.GOLD_CONFIRM_SIZE)
            TurnDisplayState.WAITING, TurnDisplayState.PREACTION -> reading.roles.isEmpty()
            TurnDisplayState.UNKNOWN -> false
        }
        if (stopped || !originSafe || foregroundPackage.isNullOrBlank() ||
            windowGeneration == null || windowGeneration < 0 ||
            signal.captureEpoch < 0 || signal.frameSequence <= 0 ||
            signal.capturedAtMs < 0 || signal.capturedAtMs > nowMs ||
            nowMs - signal.capturedAtMs > maximumFrameAgeMs || !rolesOk) {
            return rejected("Нет свежих согласованных показаний о кнопках/окне или STOP")
        }
        val nextContext = Context(signal.captureEpoch, foregroundPackage, windowGeneration)
        if (context != null && context != nextContext) return rejected("Смена Android-окна или эпохи захвата")
        if (lastFrame >= 0 && (signal.frameSequence <= lastFrame ||
                signal.capturedAtMs <= lastAt ||
                signal.capturedAtMs - lastAt > maximumGapMs))
            return rejected("Повторный, переставленный или слишком поздний кадр")
        context = nextContext
        lastFrame = signal.frameSequence
        lastAt = signal.capturedAtMs
        val current = reading.state
        samples = if (candidate == current) (samples + 1).coerceAtMost(consecutiveSamples) else 1
        candidate = current
        fun report(phase: HeroTurnPhase, why: String) = HeroTurnLifecycleReport(
            phase, why, signal.captureEpoch, signal.frameSequence, windowGeneration, episode)
        if (samples < consecutiveSamples) return report(HeroTurnPhase.COLLECTING,
            "Ожидается второй независимый кадр с тем же состоянием")
        if (stable == current) return report(when {
            !anchored -> HeroTurnPhase.NO_BASELINE
            current == TurnDisplayState.BET_SIZING && active -> HeroTurnPhase.SIZING_VISUAL
            active -> HeroTurnPhase.ACTIVE_VISUAL
            else -> HeroTurnPhase.WAITING_VISUAL
        }, "Состояние экрана устойчиво; это не игровой ID")
        stable = current
        return when (current) {
            TurnDisplayState.WAITING, TurnDisplayState.PREACTION -> {
                if (active) {
                    active = false
                    anchored = true
                    report(HeroTurnPhase.TURN_DISAPPEARED_VISUAL,
                        "Активная панель исчезла в двух кадрах; результат действия не установлен")
                } else {
                    anchored = true
                    report(HeroTurnPhase.WAITING_VISUAL, "Устойчивый фон ожидания установлен")
                }
            }
            TurnDisplayState.READY -> {
                if (!anchored) report(HeroTurnPhase.NO_BASELINE,
                    "Активные кнопки уже были видны; начало хода не установлено")
                else if (active) report(HeroTurnPhase.ACTIVE_VISUAL,
                    "После выбора размера снова видна панель; новый ход не объявлен")
                else {
                    active = true
                    episode++
                    report(HeroTurnPhase.TURN_APPEARED_VISUAL,
                        "После ожидания появилась устойчивая активная панель")
                }
            }
            TurnDisplayState.BET_SIZING -> {
                if (active) report(HeroTurnPhase.SIZING_VISUAL,
                    "Панель выбора размера, это ещё не конец хода")
                else report(HeroTurnPhase.NO_BASELINE,
                    "Панель размера без подтверждённого предыдущего хода")
            }
            TurnDisplayState.UNKNOWN -> rejected("Неопределённый экран")
        }
    }
}
