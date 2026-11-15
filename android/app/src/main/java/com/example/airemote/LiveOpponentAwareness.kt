package com.example.airemote

/**
 * Read-only, in-hand opponent evidence collector.
 *
 * Evidence is produced by a FUTURE independent detector: this component does not
 * infer a table/hand ID from a package name, OCR label, stack size or seat.
 * Nothing here commits to SQLite or authorizes a tap. The only persistence path
 * remains VerifiedHandCompletionGate -> PostHandLearner -> HandLearningStore.
 */
data class VerifiedOpponentHandStart(
    val tableKey: String,
    val handKey: String,
    val captureEpoch: Long,
    val windowGeneration: Long,
    val firstFrame: Long,
    val secondFrame: Long,
    val firstCapturedAtMs: Long,
    val secondCapturedAtMs: Long,
    val inspectedAtMs: Long,
    val sourceVerified: Boolean,
    val stableTableAndHandVerified: Boolean
)

data class ConfirmedOpponentAction(
    val tableKey: String,
    val handKey: String,
    val captureEpoch: Long,
    val windowGeneration: Long,
    val opponentKey: String,
    /** Seat-only or screen-coordinate identities must NOT be saved across hands. */
    val stableOpponentIdentityVerified: Boolean,
    /** Stable ID for this action in this hand. Distinct from frame number. */
    val actionKey: String,
    val street: String,
    val action: String,
    val facingBetVerified: Boolean? = null,
    val firstFrame: Long,
    val confirmedFrame: Long,
    val firstCapturedAtMs: Long,
    val confirmedCapturedAtMs: Long,
    val inspectedAtMs: Long,
    val sourceVerified: Boolean,
    val actionConfirmedByIndependentFrames: Boolean
)

enum class OpponentEvidenceResult { ACCEPTED, DUPLICATE, REJECTED, INVALIDATED }

data class InHandOpponentSummary(
    val opponentKey: String,
    val stableIdentityVerified: Boolean,
    val verifiedActions: Int,
    val voluntarilyEnteredPreflop: Boolean?,
    val preflopRaised: Boolean?,
    val postflopBetsOrRaises: Int?,
    val postflopCalls: Int?,
    val foldedFacingBet: Boolean?,
    val facedBet: Boolean?
) {
    fun describe(): String = buildString {
        append(opponentKey.take(60)).append(": подтверждено действий ").append(verifiedActions)
        append(", VPIP=").append(voluntarilyEnteredPreflop?.toString() ?: "неизвестно")
        append(", PFR=").append(preflopRaised?.toString() ?: "неизвестно")
        append(", постфлоп агрессия=").append(postflopBetsOrRaises ?: "неизвестно")
        append(", коллы=").append(postflopCalls ?: "неизвестно")
        append(", fold-vs-bet=").append(foldedFacingBet?.toString() ?: "неизвестно")
        append(". Неполная выборка: не выводить стиль из единичных действий.")
    }
}

class LiveOpponentAwareness(
    private val maximumFrameAgeMs: Long = 1200L,
    private val minimumFrameGapMs: Long = 100L,
    private val maximumProofGapMs: Long = 2500L
) {
    private var active: VerifiedOpponentHandStart? = null
    private var lastActionFrame = -1L
    private var lastActionCapturedAt = -1L
    private val seenActions = mutableMapOf<String, ConfirmedOpponentAction>()
    private val players = linkedMapOf<String, MutableList<ConfirmedOpponentAction>>()

    @Synchronized fun reset() {
        active = null
        lastActionFrame = -1L
        lastActionCapturedAt = -1L
        seenActions.clear()
        players.clear()
    }

    private fun validKey(s: String): Boolean = s.isNotBlank() && s.length <= 128 && s.none { it.isISOControl() }
    private fun validTime(first: Long, second: Long, observed: Long): Boolean =
        first >= 0 && second >= first && observed >= second &&
            second - first in minimumFrameGapMs..maximumProofGapMs &&
            observed - second <= maximumFrameAgeMs

    @Synchronized fun begin(start: VerifiedOpponentHandStart): OpponentEvidenceResult {
        // Starting another context always drops all uncommitted observations.
        reset()
        if (!start.sourceVerified || !start.stableTableAndHandVerified ||
            !validKey(start.tableKey) || !validKey(start.handKey) ||
            start.captureEpoch < 0 || start.windowGeneration < 0 ||
            start.firstFrame < 0 || start.secondFrame <= start.firstFrame ||
            !validTime(start.firstCapturedAtMs, start.secondCapturedAtMs, start.inspectedAtMs))
            return OpponentEvidenceResult.REJECTED
        active = start
        lastActionFrame = start.secondFrame
        lastActionCapturedAt = start.secondCapturedAtMs
        return OpponentEvidenceResult.ACCEPTED
    }

    @Synchronized fun observe(event: ConfirmedOpponentAction): OpponentEvidenceResult {
        val hand = active ?: return OpponentEvidenceResult.REJECTED
        if (hand.tableKey != event.tableKey || hand.handKey != event.handKey ||
            hand.captureEpoch != event.captureEpoch || hand.windowGeneration != event.windowGeneration) {
            reset()
            return OpponentEvidenceResult.INVALIDATED
        }
        if (!event.sourceVerified || !event.actionConfirmedByIndependentFrames) {
            reset()
            return OpponentEvidenceResult.INVALIDATED
        }
        if (!validKey(event.actionKey) || !validKey(event.opponentKey) ||
            event.firstFrame <= hand.firstFrame || event.confirmedFrame <= event.firstFrame ||
            !validTime(event.firstCapturedAtMs, event.confirmedCapturedAtMs, event.inspectedAtMs) ||
            event.street !in setOf("PREFLOP", "FLOP", "TURN", "RIVER") ||
            event.action !in setOf("FOLD", "CHECK", "CALL", "BET", "RAISE") ||
            (event.street == "PREFLOP" && event.action == "BET") ||
            (event.action == "CHECK" && event.facingBetVerified == true) ||
            (event.action == "BET" && event.facingBetVerified == true) ||
            (event.action == "CALL" && event.facingBetVerified == false))
            return OpponentEvidenceResult.REJECTED
        seenActions[event.actionKey]?.let {
            if (it == event) return OpponentEvidenceResult.DUPLICATE
            // Conflicting evidence for the same immutable action invalidates the hand.
            reset()
            return OpponentEvidenceResult.INVALIDATED
        }
        // A fresh action must not recycle either confirmation frame from an
        // earlier action. Otherwise two UI interpretations could count twice.
        if (event.firstFrame <= lastActionFrame ||
            event.confirmedFrame <= lastActionFrame ||
            event.firstCapturedAtMs <= lastActionCapturedAt ||
            event.confirmedCapturedAtMs <= lastActionCapturedAt ||
            seenActions.size >= 240 ||
            (event.opponentKey !in players && players.size >= 12))
            return OpponentEvidenceResult.REJECTED
        val previous = players[event.opponentKey]?.firstOrNull()
        if (previous != null && previous.stableOpponentIdentityVerified != event.stableOpponentIdentityVerified) {
            reset()
            return OpponentEvidenceResult.INVALIDATED
        }
        seenActions[event.actionKey] = event
        players.getOrPut(event.opponentKey) { mutableListOf() }.add(event)
        lastActionFrame = event.confirmedFrame
        lastActionCapturedAt = event.confirmedCapturedAtMs
        return OpponentEvidenceResult.ACCEPTED
    }

    private fun summarize(key: String, events: List<ConfirmedOpponentAction>): InHandOpponentSummary {
        val pre = events.filter { it.street == "PREFLOP" }
        val post = events.filter { it.street != "PREFLOP" }
        val facing = events.filter { it.facingBetVerified == true &&
            it.action in setOf("FOLD", "CALL", "RAISE") }
        // The v1 SQLite summary represents at most ONE response per hand.
        // With multiple independently confirmed facing-bet decisions we know
        // there were opportunities, but cannot truthfully compress them to
        // a single Boolean fold outcome. Do not inflate fold-vs-bet.
        val singleFacingResponse = facing.singleOrNull()
        return InHandOpponentSummary(
            opponentKey = key,
            stableIdentityVerified = events.all { it.stableOpponentIdentityVerified },
            verifiedActions = events.size,
            voluntarilyEnteredPreflop = if (pre.any { it.action in setOf("CALL", "RAISE") }) true else null,
            preflopRaised = if (pre.any { it.action == "RAISE" }) true else null,
            postflopBetsOrRaises = post.count { it.action == "BET" || it.action == "RAISE" }.takeIf { it > 0 },
            postflopCalls = post.count { it.action == "CALL" }.takeIf { it > 0 },
            foldedFacingBet = when (singleFacingResponse?.action) {
                "FOLD" -> true
                "CALL", "RAISE" -> false
                else -> null
            },
            facedBet = if (facing.isNotEmpty()) true else null
        )
    }

    @Synchronized fun snapshot(): List<InHandOpponentSummary> =
        players.map { (key, events) -> summarize(key, events) }

    @Synchronized fun contextForDecision(persisted: (List<String>) -> String): String {
        val stable = snapshot().filter { it.stableIdentityVerified }.map { it.opponentKey }
        val history = try { persisted(stable) } catch (_: Exception) { "История недоступна" }
        return buildString {
            append("Наблюдения ТЕКУЩЕЙ раздачи (не сохранены, только подтверждённые события).\n")
            snapshot().take(12).forEach { append(it.describe()).append('\n') }
            append("История ПРОШЛЫХ завершённых раздач:\n")
            append(history)
        }.take(2800)
    }

    /**
     * Add only observed POSITIVE facts to a candidate receipt. A terminal frame
     * does NOT prove completion by itself; VerifiedHandCompletionGate must still
     * see two matching positive terminal frames before any SQLite transaction.
     */
    @Synchronized fun proposalFromTrustedTerminal(frame: VerifiedHandBoundaryFrame): VerifiedFinishedHand? {
        val hand = active ?: return null
        if (!frame.sourceVerified || !frame.stableHandKeyVerified ||
            !frame.terminalCueVerified || frame.terminalCueKey.isNullOrBlank() ||
            frame.phase != HandBoundaryPhase.TERMINAL || frame.operatorStopped ||
            frame.tableKey != hand.tableKey || frame.handKey != hand.handKey ||
            frame.captureEpoch != hand.captureEpoch ||
            frame.windowGeneration != hand.windowGeneration ||
            frame.frameSequence <= lastActionFrame ||
            frame.inspectedAtElapsedMs < hand.secondCapturedAtMs) return null
        val candidate = VerifiedFinishedHand(hand.tableKey, hand.handKey, hand.captureEpoch,
            hand.firstFrame, frame.frameSequence, frame.inspectedAtElapsedMs,
            sourceVerified = true, terminalConfirmedByIndependentFrames = true,
            opponents = emptyList())
        return prepareForVerifiedTerminal(candidate, frame.windowGeneration)
    }

    /**
     * An external verified terminal observation supplies the receipt. This
     * merely attaches positive, independently confirmed action facts. Never
     * invents a showdown, false VPIP/PFR or a hand result from missing events.
     */
    @Synchronized fun prepareForVerifiedTerminal(receipt: VerifiedFinishedHand,
        windowGeneration: Long): VerifiedFinishedHand? {
        val hand = active ?: return null
        if (!receipt.sourceVerified || !receipt.terminalConfirmedByIndependentFrames ||
            receipt.tableKey != hand.tableKey || receipt.handKey != hand.handKey ||
            receipt.captureEpoch != hand.captureEpoch || windowGeneration != hand.windowGeneration ||
            receipt.firstFrame != hand.firstFrame || receipt.terminalFrame <= lastActionFrame ||
            receipt.finishedAtElapsedMs < hand.secondCapturedAtMs ||
            receipt.opponents.isNotEmpty()) return null
        val enriched = snapshot().map {
            OpponentHandFacts(
                opponentKey = it.opponentKey,
                identityVerified = it.stableIdentityVerified,
                vpip = it.voluntarilyEnteredPreflop,
                preflopRaise = it.preflopRaised,
                postflopBetsOrRaises = it.postflopBetsOrRaises,
                postflopCalls = it.postflopCalls,
                facedBet = it.facedBet,
                foldedWhenFacingBet = it.foldedFacingBet
            )
        }
        return receipt.copy(opponents = enriched)
    }
}
