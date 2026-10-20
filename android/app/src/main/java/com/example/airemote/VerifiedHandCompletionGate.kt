package com.example.airemote

/**
 * Conservative bridge between an independently VERIFIED source/terminal detector
 * and immediate on-phone learning. Does not inspect pixels, infer a hand ID, or
 * operate the game. In particular, disappearing cards are NOT terminal evidence.
 *
 * No production producer of VerifiedHandBoundaryFrame exists yet: the live
 * capture pipeline MUST NOT set the verification flags without genuine evidence.
 */
enum class HandBoundaryPhase { ACTIVE, TERMINAL }
enum class HandBoundaryStatus {
    WAITING_FOR_ACTIVE, ACTIVE_CONFIRMED, WAITING_FOR_TERMINAL,
    COMMITTED, ALREADY_COMMITTED, REJECTED, STOPPED, STORAGE_ERROR
}

data class VerifiedHandBoundaryFrame(
    val tableKey: String,
    val handKey: String,
    val captureEpoch: Long,
    val frameSequence: Long,
    val capturedAtElapsedMs: Long,
    val inspectedAtElapsedMs: Long,
    /** Source app/foreground MUST be independently verified upstream. */
    val sourceVerified: Boolean,
    /** Must be a stable real hand ID, NOT a synthetic local frame counter. */
    val stableHandKeyVerified: Boolean,
    val recognitionConfidence: Double,
    val phase: HandBoundaryPhase,
    /** Positive result/settlement cue, NOT merely cards disappearing. */
    val terminalCueVerified: Boolean = false,
    val terminalCueKey: String? = null,
    val operatorStopped: Boolean = false,
    /** Required for in-hand opponent evidence. Older producers default to unverified. */
    val windowGeneration: Long = -1L
)

data class HandBoundaryDecision(val status: HandBoundaryStatus, val reason: String,
                                val learningResult: HandLearningResult? = null)

class VerifiedHandCompletionGate(
    private val saveFinishedHand: (VerifiedFinishedHand) -> HandLearningResult,
    private val minimumConfidence: Double = 0.95,
    private val maximumFrameAgeMs: Long = 1200L,
    private val minimumIndependentGapMs: Long = 100L,
    private val maximumConfirmationGapMs: Long = 2500L
) {
    private var currentTable: String? = null
    private var currentHand: String? = null
    private var currentEpoch: Long? = null
    private var lastFrame = -1L
    private var lastObservedMs = -1L
    private var activeFirstFrame = -1L
    private var activeFirstAt = -1L
    private var activeConfirmed = false
    private var terminalFirstFrame = -1L
    private var terminalFirstAt = -1L
    private var terminalCue: String? = null
    private var finished = false

    @Synchronized fun reset() {
        currentTable = null; currentHand = null; currentEpoch = null
        lastFrame = -1L; lastObservedMs = -1L
        activeFirstFrame = -1L; activeFirstAt = -1L
        activeConfirmed = false
        terminalFirstFrame = -1L; terminalFirstAt = -1L
        terminalCue = null; finished = false
    }

    private fun decision(s: HandBoundaryStatus, reason: String, result: HandLearningResult? = null) =
        HandBoundaryDecision(s, reason, result)

    @Synchronized fun ingest(frame: VerifiedHandBoundaryFrame,
                             facts: VerifiedFinishedHand? = null): HandBoundaryDecision {
        if (frame.operatorStopped) {
            reset()
            return decision(HandBoundaryStatus.STOPPED, "STOP: событие не записано")
        }
        if (!frame.sourceVerified || !frame.stableHandKeyVerified ||
            frame.tableKey.isBlank() || frame.handKey.isBlank() ||
            frame.tableKey.length > 128 || frame.handKey.length > 128 ||
            frame.captureEpoch < 0 || frame.frameSequence < 0 ||
            frame.capturedAtElapsedMs < 0 || frame.inspectedAtElapsedMs < frame.capturedAtElapsedMs ||
            frame.inspectedAtElapsedMs - frame.capturedAtElapsedMs > maximumFrameAgeMs ||
            !frame.recognitionConfidence.isFinite() ||
            frame.recognitionConfidence !in minimumConfidence..1.0) {
            reset()
            return decision(HandBoundaryStatus.REJECTED, "Источник, ID, время или уверенность не подтверждены")
        }
        if (currentTable != frame.tableKey || currentHand != frame.handKey ||
            currentEpoch != frame.captureEpoch) {
            // No cross-table/capture/hand inheritance. An old hand is abandoned,
            // never silently marked as finished by the appearance of the next one.
            reset()
            currentTable = frame.tableKey
            currentHand = frame.handKey
            currentEpoch = frame.captureEpoch
        }
        if (frame.frameSequence <= lastFrame || frame.inspectedAtElapsedMs <= lastObservedMs) {
            reset()
            return decision(HandBoundaryStatus.REJECTED, "Повтор/перестановка кадров: состояние сброшено")
        }
        lastFrame = frame.frameSequence
        lastObservedMs = frame.inspectedAtElapsedMs
        if (finished) return decision(HandBoundaryStatus.ALREADY_COMMITTED, "Раздача уже обработана")

        if (frame.phase == HandBoundaryPhase.ACTIVE) {
            terminalFirstFrame = -1L; terminalFirstAt = -1L; terminalCue = null
            if (activeConfirmed) return decision(HandBoundaryStatus.ACTIVE_CONFIRMED, "Раздача наблюдается")
            if (activeFirstFrame < 0 || frame.inspectedAtElapsedMs - activeFirstAt > maximumConfirmationGapMs) {
                activeFirstFrame = frame.frameSequence
                activeFirstAt = frame.inspectedAtElapsedMs
                return decision(HandBoundaryStatus.WAITING_FOR_ACTIVE, "Нужен второй активный кадр")
            }
            if (frame.inspectedAtElapsedMs - activeFirstAt < minimumIndependentGapMs)
                return decision(HandBoundaryStatus.WAITING_FOR_ACTIVE, "Кадры слишком близки")
            activeConfirmed = true
            return decision(HandBoundaryStatus.ACTIVE_CONFIRMED, "Два разных кадра активной раздачи")
        }

        if (!activeConfirmed) return decision(HandBoundaryStatus.REJECTED,
            "Нет двух подтверждённых кадров активной раздачи")
        if (!frame.terminalCueVerified || frame.terminalCueKey.isNullOrBlank() ||
            frame.terminalCueKey.length > 64) {
            terminalFirstFrame = -1L; terminalFirstAt = -1L; terminalCue = null
            return decision(HandBoundaryStatus.REJECTED, "Нет положительного подтверждения финала")
        }
        val cue = frame.terminalCueKey
        if (terminalFirstFrame < 0 || cue != terminalCue ||
            frame.inspectedAtElapsedMs - terminalFirstAt > maximumConfirmationGapMs) {
            terminalFirstFrame = frame.frameSequence
            terminalFirstAt = frame.inspectedAtElapsedMs
            terminalCue = cue
            return decision(HandBoundaryStatus.WAITING_FOR_TERMINAL, "Нужен второй терминальный кадр")
        }
        if (frame.inspectedAtElapsedMs - terminalFirstAt < minimumIndependentGapMs)
            return decision(HandBoundaryStatus.WAITING_FOR_TERMINAL, "Терминальные кадры слишком близки")
        val verified = if (facts == null) {
            // Unknown facts remain UNKNOWN. The hand receipt is still saved now.
            VerifiedFinishedHand(frame.tableKey, frame.handKey, frame.captureEpoch,
                activeFirstFrame, frame.frameSequence, frame.inspectedAtElapsedMs,
                sourceVerified = true, terminalConfirmedByIndependentFrames = true,
                opponents = emptyList())
        } else {
            if (facts.tableKey != frame.tableKey || facts.handKey != frame.handKey ||
                facts.captureEpoch != frame.captureEpoch ||
                facts.firstFrame != activeFirstFrame || facts.terminalFrame != frame.frameSequence ||
                facts.finishedAtElapsedMs != frame.inspectedAtElapsedMs ||
                !facts.sourceVerified || !facts.terminalConfirmedByIndependentFrames) {
                return decision(HandBoundaryStatus.REJECTED, "Факты относятся к другому событию")
            }
            facts
        }
        val outcome = try { saveFinishedHand(verified) }
                      catch (_: Exception) { HandLearningResult.STORAGE_ERROR }
        return when (outcome) {
            HandLearningResult.APPLIED -> {
                finished = true
                decision(HandBoundaryStatus.COMMITTED, "Сохранено до следующего решения", outcome)
            }
            HandLearningResult.ALREADY_SEEN -> {
                finished = true
                decision(HandBoundaryStatus.ALREADY_COMMITTED, "Уже сохранено ранее", outcome)
            }
            HandLearningResult.REJECTED -> decision(HandBoundaryStatus.REJECTED,
                "Проверка фактов отклонила раздачу", outcome)
            HandLearningResult.STORAGE_ERROR -> decision(HandBoundaryStatus.STORAGE_ERROR,
                "Не удалось атомарно записать историю; разрешён повтор записи", outcome)
        }
    }
}
