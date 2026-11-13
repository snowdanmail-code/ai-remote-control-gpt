package com.example.airemote

/**
 * Correlates an independently verified game action with a separately
 * verified seat/actor binding. OCR labels and popup nicknames CANNOT create
 * this proof. No screen coordinate alone is a stable identity.
 */
data class T047SeatActorProof(
    val sourcePackage: String,
    val tableKey: String,
    val handKey: String,
    val captureEpoch: Long,
    val windowGeneration: Long,
    val seat: Int,
    val actorKey: String,
    val actionKey: String,
    val confirmedFrame: Long,
    val seatActorIndependentlyVerified: Boolean,
    val sourceIndependentlyVerified: Boolean
)

enum class T047OpponentActionStatus {
    ACCEPTED,
    UNVERIFIED_BINDING,
    WRONG_SESSION_OR_ACTION,
    UNKNOWN_OR_AMBIGUOUS_SEAT,
    NOT_CONFIRMED_BY_HAND_COLLECTOR,
    MEMORY_REJECTED
}

/**
 * Safe integration boundary. The independent live-hand collector must first
 * accept the event; only then is a scoped per-seat observation changed.
 *
 * The caller must NOT manufacture seatActorIndependentlyVerified from OCR,
 * avatar geometry or a seat number. No live proof producer is wired yet.
 */
object T047VerifiedOpponentActionBridge {
    fun ingest(
        memory: T046TableOpponentMemory,
        session: T046TableSession,
        expectedTableKey: String,
        event: ConfirmedOpponentAction,
        binding: T047SeatActorProof,
        acceptWithExistingHandCollector: (ConfirmedOpponentAction) -> OpponentEvidenceResult
    ): T047OpponentActionStatus {
        if (!binding.seatActorIndependentlyVerified ||
            !binding.sourceIndependentlyVerified ||
            !event.sourceVerified || !event.actionConfirmedByIndependentFrames)
            return T047OpponentActionStatus.UNVERIFIED_BINDING
        if (!session.valid() || session.expectedPackage != binding.sourcePackage ||
            session.captureEpoch != binding.captureEpoch ||
            session.windowGeneration != binding.windowGeneration ||
            expectedTableKey.isBlank() ||
            expectedTableKey != event.tableKey ||
            expectedTableKey != binding.tableKey ||
            event.handKey.isBlank() || event.handKey != binding.handKey ||
            event.captureEpoch != binding.captureEpoch ||
            event.windowGeneration != binding.windowGeneration ||
            event.opponentKey != binding.actorKey ||
            event.actionKey != binding.actionKey ||
            event.confirmedFrame != binding.confirmedFrame ||
            binding.seat !in 0..9)
            return T047OpponentActionStatus.WRONG_SESSION_OR_ACTION

        val currentSeat = memory.snapshot(session).firstOrNull {
            it.seat == binding.seat
        } ?: return T047OpponentActionStatus.UNKNOWN_OR_AMBIGUOUS_SEAT
        if (currentSeat.ambiguousIdentity ||
            currentSeat.participation == T046HandParticipation.LEFT_TABLE_CONFIRMED ||
            (currentSeat.lastVerifiedHand == event.handKey &&
                currentSeat.participation == T046HandParticipation.FOLDED_CONFIRMED))
            return T047OpponentActionStatus.UNKNOWN_OR_AMBIGUOUS_SEAT

        // No permanent identity is inferred from a nickname. The separate
        // hand collector can still process non-stable within-hand identities,
        // but ONLY the independently bound current seat is updated here.
        val next = when (event.action) {
            "FOLD" -> T046HandParticipation.FOLDED_CONFIRMED
            "CHECK", "CALL", "BET", "RAISE" ->
                T046HandParticipation.ACTIVE_CONFIRMED
            else -> return T047OpponentActionStatus.WRONG_SESSION_OR_ACTION
        }
        if (acceptWithExistingHandCollector(event) != OpponentEvidenceResult.ACCEPTED)
            return T047OpponentActionStatus.NOT_CONFIRMED_BY_HAND_COLLECTOR

        // The source and action have been positively verified by the existing
        // hand collector. This starts/continues ONLY the current hand.
        memory.beginVerifiedHand(session, event.handKey,
            independentlyVerified = true)
        return if (memory.applyVerifiedEvent(session, event.handKey,
                event.actionKey, binding.seat, next,
                T046Evidence.VERIFIED_HAND_EVENT))
            T047OpponentActionStatus.ACCEPTED
        else T047OpponentActionStatus.MEMORY_REJECTED
    }
}
