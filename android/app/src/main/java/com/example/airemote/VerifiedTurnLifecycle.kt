package com.example.airemote

/**
 * Conservative timeline for an ORIGINAL game's button strip. An authenticated
 * table/hand source is required; two coherent frames prove only VISIBILITY.
 * No IDs generated here authorize gestures, strategy decisions or a new hand.
 */
enum class TurnCyclePhase { UNKNOWN, OPENING, VISIBLE_DIAGNOSTIC, CLOSING, CLOSED, SUSPENDED }

data class TurnCycleFrame(
    val tableId: String,
    val handId: String,
    val independentlyVerifiedIdentity: Boolean,
    val foregroundVerified: Boolean,
    val captureEpoch: Long,
    val windowGeneration: Long,
    val frameSequence: Long,
    val capturedAtMs: Long,
    val inspectedAtMs: Long,
    val stopped: Boolean,
    val visual: TurnDisplayState,
    val actionLabelsStable: Boolean,
    val actionLabelsConsistent: Boolean
)

data class TurnCycleReport(
    val phase: TurnCyclePhase,
    val visibleOrdinal: Long,
    val latestFrame: Long,
    val explanation: String
) {
    /** Visual turn lifecycle is never proof of turn identity or permission. */
    val authorizesTap: Boolean get() = false
    val independentlyVerifiesTurn: Boolean get() = false
}

class VerifiedTurnLifecycle(
    private val maximumFrameAgeMs: Long = 1_500L,
    private val maximumGapMs: Long = 2_800L,
    private val minimumSeparationMs: Long = 100L
) {
    private data class Source(val table: String, val hand: String, val epoch: Long, val window: Long)
    private var source: Source? = null
    private var lastSeq = -1L
    private var lastAt = -1L
    private var lastInspected = -1L
    private var activeRun = 0
    private var inactiveRun = 0
    private var ordinal = 0L
    private var phase = TurnCyclePhase.UNKNOWN
    private var lastExplanation = "Ожидание кадров"

    @Synchronized fun reset(reason: String = "STOP / сброс") {
        source = null
        lastSeq = -1L
        lastAt = -1L
        lastInspected = -1L
        activeRun = 0
        inactiveRun = 0
        ordinal = 0L
        phase = TurnCyclePhase.UNKNOWN
        lastExplanation = reason
    }

    @Synchronized fun snapshot(nowMs: Long): TurnCycleReport {
        if (lastInspected >= 0 && (nowMs < lastInspected ||
                nowMs - lastInspected > maximumFrameAgeMs)) {
            suspend("Наблюдение устарело")
        }
        return TurnCycleReport(phase, ordinal, lastSeq, lastExplanation)
    }

    private fun suspend(reason: String) {
        phase = TurnCyclePhase.SUSPENDED
        activeRun = 0
        inactiveRun = 0
        lastExplanation = reason
    }

    @Synchronized fun observe(frame: TurnCycleFrame): TurnCycleReport {
        fun report() = TurnCycleReport(phase, ordinal, lastSeq, lastExplanation)
        if (frame.stopped) {
            reset("STOP")
            return report()
        }
        if (!frame.independentlyVerifiedIdentity || !frame.foregroundVerified ||
            frame.tableId.isBlank() || frame.handId.isBlank() || frame.captureEpoch < 0L ||
            frame.windowGeneration < 0L || frame.frameSequence <= 0L ||
            frame.capturedAtMs < 0L || frame.inspectedAtMs < frame.capturedAtMs ||
            frame.inspectedAtMs - frame.capturedAtMs > maximumFrameAgeMs ||
            !frame.actionLabelsConsistent || frame.visual == TurnDisplayState.UNKNOWN ||
            frame.visual == TurnDisplayState.BET_SIZING
        ) {
            suspend("Источник, окно, время, надписи или режим выбора ставки не подтверждены")
            return report()
        }
        val key = Source(frame.tableId, frame.handId, frame.captureEpoch, frame.windowGeneration)
        if (source != key) {
            // A newly authenticated context resets previous hand evidence.
            // If an unauthenticated observation suspended a NEW session, a
            // later valid frame alone must not clear that uncertainty.
            if (source != null || phase != TurnCyclePhase.SUSPENDED) {
                reset("Новый стол, раздача, захват или окно")
            }
            source = key
        } else {
            if (frame.frameSequence <= lastSeq || frame.capturedAtMs <= lastAt ||
                frame.inspectedAtMs < lastInspected) {
                suspend("Повторный или переставленный кадр")
                return report()
            }
            if (lastAt >= 0 && frame.capturedAtMs - lastAt > maximumGapMs) {
                suspend("Разрыв между кадрами")
            }
        }
        val properlySeparated = lastAt >= 0 &&
            frame.capturedAtMs - lastAt in minimumSeparationMs..maximumGapMs
        lastSeq = frame.frameSequence
        lastAt = frame.capturedAtMs
        lastInspected = frame.inspectedAtMs
        val active = frame.visual == TurnDisplayState.READY && frame.actionLabelsStable
        // PREACTION and WAITING are independent non-active cues. If READY is seen
        // without stable exact labels, suspend instead of interpreting absence.
        if (frame.visual == TurnDisplayState.READY && !frame.actionLabelsStable) {
            suspend("Активные цвета без согласованных надписей")
            return report()
        }
        if (active) {
            if (phase == TurnCyclePhase.SUSPENDED) {
                lastExplanation = "После неопределённости необходимо увидеть закрытый ход"
                return report()
            }
            val briefFlicker = phase == TurnCyclePhase.CLOSING && inactiveRun == 1 && properlySeparated
            inactiveRun = 0
            if (!properlySeparated) activeRun = 1 else activeRun++
            if (briefFlicker) {
                phase = TurnCyclePhase.VISIBLE_DIAGNOSTIC
                lastExplanation = "Однокадровое исчезновение кнопок не создаёт новый ход"
            } else if (phase == TurnCyclePhase.VISIBLE_DIAGNOSTIC) {
                lastExplanation = "Панель текущего хода по-прежнему видна"
            } else if (activeRun >= 2 && (phase == TurnCyclePhase.UNKNOWN || phase == TurnCyclePhase.OPENING || phase == TurnCyclePhase.CLOSED)) {
                ordinal++
                phase = TurnCyclePhase.VISIBLE_DIAGNOSTIC
                lastExplanation = "Видимый ход подтверждён; номер только диагностический"
            } else {
                phase = TurnCyclePhase.OPENING
                lastExplanation = "Ожидается второй независимый кадр активных кнопок"
            }
        } else {
            activeRun = 0
            if (!properlySeparated) inactiveRun = 1 else inactiveRun++
            if (phase == TurnCyclePhase.VISIBLE_DIAGNOSTIC || phase == TurnCyclePhase.CLOSING) {
                if (inactiveRun >= 2) {
                    phase = TurnCyclePhase.CLOSED
                    lastExplanation = "Два кадра без активных кнопок: видимый ход закрыт"
                } else {
                    phase = TurnCyclePhase.CLOSING
                    lastExplanation = "Один кадр без кнопок не доказывает окончание хода"
                }
            } else if (inactiveRun >= 2) {
                phase = TurnCyclePhase.CLOSED
                lastExplanation = "Подтверждён неактивный экран"
            } else {
                if (phase != TurnCyclePhase.SUSPENDED) phase = TurnCyclePhase.UNKNOWN
                lastExplanation = "Ждём подтверждения отсутствия активного хода"
            }
        }
        return report()
    }
}
