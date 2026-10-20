package com.example.airemote

/**
 * Hand-state tracking from VERIFIED, already-decoded poker UI observations.
 * No image inference happens here. An observation must come from an independently
 * validated target/source detector; MediaProjection alone does not certify 1win.
 *
 * A transition needs two temporally close, independent frames. Invalid / stale /
 * conflicting input makes the context untrusted. This class NEVER presses buttons
 * or infers the user's turn from the number of community cards.
 */
enum class HandStage { UNKNOWN, WAITING, PREFLOP, FLOP, TURN, RIVER, SHOWDOWN, FINISHED }

data class VerifiedTableObservation(
    val tableId: String,
    val captureEpoch: Long,
    val frameSequence: Long,
    val capturedAtMs: Long,
    val inspectedAtMs: Long,
    val sourceVerified: Boolean,
    val recognitionConfidence: Double,
    /** null = unreadable; empty = positively observed absent. */
    val holeCards: List<String>?,
    val communityCards: List<String>?,
    val waitingIndicator: Boolean = false,
    val showdownIndicator: Boolean = false,
    val handFinishedIndicator: Boolean = false,
    val newHandEventId: String? = null,
    /** Optional reliable hand identifier reported by a verified observation. */
    val handToken: String? = null
)

data class HandStateSnapshot(
    val stage: HandStage,
    /** True only for a recently verified and internally consistent observation. */
    val trustworthyNow: Boolean,
    val handSerial: Long,
    val lastAcceptedFrame: Long,
    val reason: String
)

class HandStateTracker(
    private val minimumConfidence: Double = 0.90,
    private val maximumFrameAgeMs: Long = 1_200L,
    private val maximumConfirmationGapMs: Long = 2_000L
) {
    private data class Candidate(
        val stage: HandStage,
        val hole: List<String>,
        val board: List<String>,
        val token: String?,
        val newHandEventId: String?
    )
    private var currentTable: String? = null
    private var currentEpoch: Long? = null
    private var lastFrame = -1L
    private var lastInspectedAt = -1L
    private var stage = HandStage.UNKNOWN
    private var trusted = false
    private var handSerial = 0L
    private var committedHole: List<String> = emptyList()
    private var committedBoard: List<String> = emptyList()
    private var committedToken: String? = null
    private var committedNewHandEventId: String? = null
    private var pending: Candidate? = null
    private var pendingCount = 0
    private var pendingAtMs = -1L
    private var explanation = "Ожидание подтверждённых наблюдений"

    @Synchronized fun reset(reason: String = "Сброс / STOP") {
        currentTable = null
        currentEpoch = null
        lastFrame = -1L
        lastInspectedAt = -1L
        stage = HandStage.UNKNOWN
        trusted = false
        handSerial = 0L
        committedHole = emptyList()
        committedBoard = emptyList()
        committedToken = null
        committedNewHandEventId = null
        pending = null
        pendingCount = 0
        pendingAtMs = -1L
        explanation = reason
    }

    /** Discard the authority of the last stage without erasing the hand progression. */
    @Synchronized fun invalidate(reason: String) {
        trusted = false
        pending = null
        pendingCount = 0
        pendingAtMs = -1L
        explanation = reason
    }

    @Synchronized fun snapshot(nowMs: Long? = null): HandStateSnapshot {
        val fresh = nowMs == null ||
            (lastInspectedAt >= 0L && nowMs >= lastInspectedAt &&
                nowMs - lastInspectedAt <= maximumFrameAgeMs)
        return HandStateSnapshot(stage, trusted && fresh, handSerial, lastFrame,
            if (fresh) explanation else "Нет свежих кадров: фаза не подтверждена")
    }

    @Synchronized fun ingest(input: VerifiedTableObservation): HandStateSnapshot {
        val source = input.tableId.trim()
        if (!input.sourceVerified || source.isEmpty()) return reject("Источник покерного стола не подтверждён")
        if (input.captureEpoch < 0 || input.frameSequence < 0 ||
            input.inspectedAtMs < input.capturedAtMs ||
            input.inspectedAtMs - input.capturedAtMs > maximumFrameAgeMs
        ) return reject("Кадр устарел или имеет некорректное время")
        if (!input.recognitionConfidence.isFinite() || input.recognitionConfidence < minimumConfidence ||
            input.recognitionConfidence > 1.0
        ) return reject("Недостаточная уверенность распознавания")
        if (currentTable != source || currentEpoch != input.captureEpoch) {
            // A different table/capture cannot inherit another hand's state.
            reset("Новый стол / новый сеанс захвата: требуется повторное подтверждение")
            currentTable = source
            currentEpoch = input.captureEpoch
        }
        if (input.frameSequence <= lastFrame || input.inspectedAtMs < lastInspectedAt) {
            return reject("Повторный или неупорядоченный кадр")
        }
        lastFrame = input.frameSequence
        lastInspectedAt = input.inspectedAtMs
        val candidate = classify(input) ?: return reject("Неоднозначные карты или фаза раздачи")
        if (!allowed(candidate)) return reject("Недопустимый переход фазы или изменение карт")

        // The old stage cannot be used for decisions while a new phase is pending.
        if (trusted && candidate.stage == stage && candidate.hole == committedHole &&
            candidate.board == committedBoard &&
            (candidate.token == null || committedToken == null || candidate.token == committedToken) &&
            !isExplicitNewHand(candidate)) {
            explanation = "Подтверждено: $stage"
            return snapshot()
        }
        if (candidate == pending && pendingAtMs >= 0 &&
            input.inspectedAtMs - pendingAtMs <= maximumConfirmationGapMs
        ) {
            pendingCount++
        } else {
            pending = candidate
            pendingCount = 1
        }
        pendingAtMs = input.inspectedAtMs
        if (pendingCount < 2) {
            trusted = false
            explanation = "Ожидание второго кадра: ${candidate.stage}"
            return snapshot()
        }
        val previous = stage
        val changesHand = candidate.stage in ACTIVE &&
            (previous == HandStage.UNKNOWN || previous == HandStage.WAITING ||
             previous == HandStage.FINISHED ||
             (candidate.stage == HandStage.PREFLOP && previous in ACTIVE && isExplicitNewHand(candidate)))
        if (changesHand) handSerial++
        stage = candidate.stage
        if (stage == HandStage.WAITING || stage == HandStage.FINISHED) {
            committedHole = emptyList()
            committedBoard = emptyList()
            committedToken = null
            committedNewHandEventId = null
        } else if (stage in ACTIVE) {
            committedHole = candidate.hole
            committedBoard = candidate.board
            if (candidate.token != null) committedToken = candidate.token
            if (candidate.newHandEventId != null) committedNewHandEventId = candidate.newHandEventId
        }
        trusted = true
        pending = null
        pendingCount = 0
        pendingAtMs = -1L
        explanation = "Подтверждено: $stage"
        return snapshot()
    }

    private fun reject(reason: String): HandStateSnapshot {
        trusted = false
        pending = null
        pendingCount = 0
        pendingAtMs = -1L
        explanation = reason
        return snapshot()
    }

    private fun classify(input: VerifiedTableObservation): Candidate? {
        if (listOf(input.waitingIndicator, input.showdownIndicator, input.handFinishedIndicator)
                .count { it } > 1) return null
        val hole = parseCards(input.holeCards) ?: return null
        val board = parseCards(input.communityCards) ?: return null
        if ((hole + board).toSet().size != hole.size + board.size) return null
        if (hole.size !in listOf(0, 2) || board.size !in listOf(0, 3, 4, 5)) return null
        val phase = when {
            input.waitingIndicator && hole.isEmpty() && board.isEmpty() -> HandStage.WAITING
            input.handFinishedIndicator -> HandStage.FINISHED
            input.showdownIndicator -> HandStage.SHOWDOWN
            hole.size != 2 -> return null
            board.size == 0 -> HandStage.PREFLOP
            board.size == 3 -> HandStage.FLOP
            board.size == 4 -> HandStage.TURN
            board.size == 5 -> HandStage.RIVER
            else -> return null
        }
        // Empty cards without a positive screen cue never mean "waiting".
        val newEventId = input.newHandEventId?.takeIf { it.isNotBlank() }
        if (newEventId != null && phase != HandStage.PREFLOP) return null
        return Candidate(phase, hole, board, input.handToken?.takeIf { it.isNotBlank() }, newEventId)
    }

    private fun parseCards(cards: List<String>?): List<String>? {
        if (cards == null) return null
        return cards.map { raw ->
            val card = raw.trim().replaceFirst(Regex("^10", RegexOption.IGNORE_CASE), "T")
            val normalized = card.uppercase().take(1) + card.lowercase().drop(1)
            if (!CARD.matches(normalized)) return null
            normalized
        }
    }

    private fun isExplicitNewHand(candidate: Candidate): Boolean =
        (candidate.newHandEventId != null &&
            candidate.newHandEventId != committedNewHandEventId) ||
            (candidate.token != null && committedToken != null &&
            candidate.token != committedToken)

    private fun allowed(candidate: Candidate): Boolean {
        val next = candidate.stage
        if (stage == HandStage.UNKNOWN) return true
        if (stage == HandStage.WAITING || stage == HandStage.FINISHED) {
            return next in setOf(HandStage.WAITING, HandStage.PREFLOP, HandStage.FINISHED)
        }
        if (next == HandStage.WAITING || next == HandStage.FINISHED) return true
        if (next == HandStage.PREFLOP && isExplicitNewHand(candidate)) return true
        if (stage == HandStage.SHOWDOWN) return next == HandStage.SHOWDOWN
        if (next == HandStage.SHOWDOWN) return true
        if (next !in ACTIVE) return false
        if (next.ordinal < stage.ordinal) return false
        if (candidate.hole != committedHole) return false
        if (candidate.board.size < committedBoard.size ||
            candidate.board.take(committedBoard.size) != committedBoard) return false
        if (committedToken != null && candidate.token != null &&
            committedToken != candidate.token) return false
        return true
    }

    private companion object {
        val ACTIVE = setOf(HandStage.PREFLOP, HandStage.FLOP, HandStage.TURN, HandStage.RIVER)
        val CARD = Regex("^[2-9TJQKA][cdhs]$")
    }
}

/** Process-local holder. Every STOP and new capture session must invalidate hand state. */
object OnDeviceHandState {
    val tracker = HandStateTracker()
    fun reset(reason: String = "Сеанс закрыт") = tracker.reset(reason)
    fun snapshot(nowMs: Long? = null) = tracker.snapshot(nowMs)
}
