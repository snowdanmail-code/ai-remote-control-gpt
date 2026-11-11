package com.example.airemote

/** T043 v1: passive data only. Nothing here authorizes actions or confirms OCR results. */
object CanonicalPokerSchema {
    const val MAJOR = 1
    const val MINOR = 0
    fun check(major: Int, minor: Int) {
        require(major == MAJOR && minor >= 0) { "Unsupported canonical schema $major.$minor" }
    }
}

enum class PokerEvidence { UNKNOWN, OCR_ONLY, CORROBORATED, SOURCE_VERIFIED, INDEPENDENT }
enum class PokerStreet { UNKNOWN, PREFLOP, FLOP, TURN, RIVER, SHOWDOWN }
enum class PokerActionKind { UNKNOWN, FOLD, CHECK, CALL, BET, RAISE, POST_BLIND, ANTE, ALL_IN, RETURN, SHOW, MUCK }
enum class PokerSeatPosition {
    BUTTON, BUTTON_SMALL_BLIND, SMALL_BLIND, BIG_BLIND, UNDER_THE_GUN,
    UTG_PLUS_ONE, UTG_PLUS_TWO, LOJACK, HIJACK, CUTOFF
}

/** namespace is a provider/tenant-specific ID; it must not be a nickname. */
data class PokerSourceIdentity(
    val namespace: String,
    val tableId: String? = null,
    val sessionId: String? = null,
    val handId: String? = null
) {
    init {
        require(namespace.isNotBlank() && namespace.length <= 128)
        require(listOf(tableId, sessionId, handId).all { it == null || (it.isNotBlank() && it.length <= 128) })
    }
}

data class CanonicalPokerCard(val rank: String, val suit: String) {
    init {
        require(rank in setOf("2", "3", "4", "5", "6", "7", "8", "9", "T", "J", "Q", "K", "A"))
        require(suit in setOf("C", "D", "H", "S"))
    }
}

/** Null is unknown; a nickname is NEVER an identity. */
data class CanonicalPokerSeat(
    val seatIndex: Int,
    val participantId: String? = null,
    val nickname: String? = null,
    val identityEvidence: PokerEvidence = PokerEvidence.UNKNOWN,
    val dealtIn: Boolean? = null,
    val activeInHand: Boolean? = null,
    val stackChips: Long? = null,
    val holeCards: List<CanonicalPokerCard>? = null
) {
    init {
        require(seatIndex >= 0)
        require(participantId == null || (participantId.isNotBlank() && participantId.length <= 128))
        require(nickname == null || nickname.length <= 128)
        require(stackChips == null || stackChips >= 0)
        require(holeCards == null || holeCards.size <= 2)
        require(activeInHand != true || dealtIn != false)
        require(identityEvidence !in setOf(PokerEvidence.SOURCE_VERIFIED, PokerEvidence.INDEPENDENT) || participantId != null)
    }
}

data class CanonicalPokerAction(
    val sequence: Long,
    val street: PokerStreet = PokerStreet.UNKNOWN,
    val actorSeat: Int? = null,
    val kind: PokerActionKind = PokerActionKind.UNKNOWN,
    /** For BET/RAISE this is the reported amount; meaning is source-defined until verified. */
    val amountChips: Long? = null,
    val evidence: PokerEvidence = PokerEvidence.UNKNOWN,
    /** Stable source-assigned ID of this game action, not a frame or list index. */
    val eventId: String
) {
    init {
        require(eventId.isNotBlank() && eventId.length <= 128)
        require(sequence >= 0 && (actorSeat == null || actorSeat >= 0) &&
            (amountChips == null || amountChips >= 0))
    }
}

data class CanonicalPokerObservation(
    val source: PokerSourceIdentity,
    val observationId: String,
    val tableCapacity: Int,
    /** Mandatory semantic rule identity, never inferred from the provider name. */
    val rulesetId: String,
    val seats: List<CanonicalPokerSeat> = emptyList(),
    /** False unless the source knows all occupied seats at this hand start. */
    val seatingComplete: Boolean = false,
    val schemaMajor: Int = CanonicalPokerSchema.MAJOR,
    val schemaMinor: Int = CanonicalPokerSchema.MINOR,
    val capturedAtMs: Long? = null,
    val buttonSeat: Int? = null,
    val heroSeat: Int? = null,
    val street: PokerStreet = PokerStreet.UNKNOWN,
    val boardCards: List<CanonicalPokerCard>? = null,
    val potChips: Long? = null,
    val actions: List<CanonicalPokerAction> = emptyList(),
    val observationEvidence: PokerEvidence = PokerEvidence.UNKNOWN
) {
    init {
        CanonicalPokerSchema.check(schemaMajor, schemaMinor)
        require(observationId.isNotBlank() && observationId.length <= 128)
        require(PokerRulesets.validId(rulesetId)) { "Invalid ruleset ID" }
        require(tableCapacity in 2..9)
        require(capturedAtMs == null || capturedAtMs >= 0)
        require(seats.size <= tableCapacity && seats.map { it.seatIndex }.distinct().size == seats.size)
        require(seats.all { it.seatIndex in 0 until tableCapacity })
        require(buttonSeat == null || buttonSeat in 0 until tableCapacity)
        require(heroSeat == null || heroSeat in 0 until tableCapacity)
        require(boardCards == null || boardCards.size <= 5)
        require(potChips == null || potChips >= 0)
        require(actions.zipWithNext().all { (a, b) -> a.sequence < b.sequence })
        require(actions.map { it.eventId }.distinct().size == actions.size) { "Duplicate eventId in hand" }
        require(actions.all { it.actorSeat == null || it.actorSeat in 0 until tableCapacity })
        val cards = (boardCards ?: emptyList()) + seats.flatMap { it.holeCards ?: emptyList() }
        require(cards.toSet().size == cards.size) { "Duplicate known card" }
        val stable = seats.filter { it.participantId != null && it.identityEvidence in
            setOf(PokerEvidence.SOURCE_VERIFIED, PokerEvidence.INDEPENDENT) }.map { it.participantId }
        require(stable.distinct().size == stable.size) { "Duplicate verified participant ID" }
    }

    /** Observation ID identifies one replayable snapshot, never an in-game action. */
    fun stableObservationKey(): String? = PokerCanonicalIdentity.observation(source, observationId)

    /** Real event identity is per (provider, session, table, hand, eventId). */
    fun stableEventKey(eventId: String): String? = PokerCanonicalIdentity.event(source, eventId)

    /** A finished hand is per (provider, session, table, hand); no frame sequence. */
    fun stableHandKey(): String? = PokerCanonicalIdentity.hand(source)

    /** Null means any active/dealt status is missing; never invent a player count. */
    fun knownActiveCount(): Int? = if (!seatingComplete || seats.any { it.activeInHand == null }) null
        else seats.count { it.activeInHand == true }

    /** Verified-scoped key or null. OCR text, seat number, and nickname cannot create a profile ID. */
    fun stableParticipantKey(seatIndex: Int): String? {
        val seat = seats.singleOrNull { it.seatIndex == seatIndex } ?: return null
        val playerId = seat.participantId ?: return null
        val sessionId = source.sessionId ?: return null
        if (seat.identityEvidence !in setOf(PokerEvidence.SOURCE_VERIFIED, PokerEvidence.INDEPENDENT) ||
            schemaMinor != CanonicalPokerSchema.MINOR) return null
        return listOf(source.namespace, rulesetId, sessionId, playerId).joinToString(":") {
            "${it.toByteArray(Charsets.UTF_8).size}:$it"
        }
    }

    /** Known dealt-in seats clockwise from button. Missing evidence => unknown, not guessed. */
    fun positions(): Map<Int, PokerSeatPosition> {
        if (!seatingComplete) return emptyMap()
        val button = buttonSeat ?: return emptyMap()
        if (seats.any { it.dealtIn == null }) return emptyMap()
        val dealt = seats.filter { it.dealtIn == true }
        if (dealt.size !in 2..9 || dealt.none { it.seatIndex == button }) return emptyMap()
        val order = dealt.sortedBy { (it.seatIndex - button + tableCapacity) % tableCapacity }
        val labels = when (order.size) {
            2 -> listOf(PokerSeatPosition.BUTTON_SMALL_BLIND, PokerSeatPosition.BIG_BLIND)
            3 -> listOf(PokerSeatPosition.BUTTON, PokerSeatPosition.SMALL_BLIND, PokerSeatPosition.BIG_BLIND)
            4 -> listOf(PokerSeatPosition.BUTTON, PokerSeatPosition.SMALL_BLIND, PokerSeatPosition.BIG_BLIND,
                PokerSeatPosition.UNDER_THE_GUN)
            5 -> listOf(PokerSeatPosition.BUTTON, PokerSeatPosition.SMALL_BLIND, PokerSeatPosition.BIG_BLIND,
                PokerSeatPosition.UNDER_THE_GUN, PokerSeatPosition.CUTOFF)
            6 -> listOf(PokerSeatPosition.BUTTON, PokerSeatPosition.SMALL_BLIND, PokerSeatPosition.BIG_BLIND,
                PokerSeatPosition.UNDER_THE_GUN, PokerSeatPosition.HIJACK, PokerSeatPosition.CUTOFF)
            7 -> listOf(PokerSeatPosition.BUTTON, PokerSeatPosition.SMALL_BLIND, PokerSeatPosition.BIG_BLIND,
                PokerSeatPosition.UNDER_THE_GUN, PokerSeatPosition.LOJACK, PokerSeatPosition.HIJACK, PokerSeatPosition.CUTOFF)
            8 -> listOf(PokerSeatPosition.BUTTON, PokerSeatPosition.SMALL_BLIND, PokerSeatPosition.BIG_BLIND,
                PokerSeatPosition.UNDER_THE_GUN, PokerSeatPosition.UTG_PLUS_ONE, PokerSeatPosition.LOJACK,
                PokerSeatPosition.HIJACK, PokerSeatPosition.CUTOFF)
            else -> listOf(PokerSeatPosition.BUTTON, PokerSeatPosition.SMALL_BLIND, PokerSeatPosition.BIG_BLIND,
                PokerSeatPosition.UNDER_THE_GUN, PokerSeatPosition.UTG_PLUS_ONE, PokerSeatPosition.UTG_PLUS_TWO,
                PokerSeatPosition.LOJACK, PokerSeatPosition.HIJACK, PokerSeatPosition.CUTOFF)
        }
        return order.mapIndexed { index, seat -> seat.seatIndex to labels[index] }.toMap()
    }
}

/** Structural verification metadata is NOT a cryptographic verifier or permission to act. */
data class VerifiedPokerHandRecord(
    val terminalObservation: CanonicalPokerObservation,
    val sourceProofRef: String,
    val handIdentityProofRef: String,
    val terminalProofRef: String,
    val sourceEvidence: PokerEvidence,
    val handIdentityEvidence: PokerEvidence,
    val terminalEvidence: PokerEvidence,
    val heroNetChips: Long? = null,
    val resultEvidence: PokerEvidence = PokerEvidence.UNKNOWN,
    val schemaMajor: Int = CanonicalPokerSchema.MAJOR,
    val schemaMinor: Int = CanonicalPokerSchema.MINOR,
    /** Duplicated on the envelope to fail closed on tampering or mixed rulesets. */
    val rulesetId: String = terminalObservation.rulesetId,
    /** Persisted stable receipt identity: no timestamps and no synthetic counters. */
    val handRecordId: String = requireNotNull(terminalObservation.stableHandKey()) {
        "Cannot create a hand receipt without source, session, table and hand IDs"
    },
    val verificationPolicyId: String = PokerVerificationPolicies.COMPLETE_RECORD_V1
) {
    init {
        CanonicalPokerSchema.check(schemaMajor, schemaMinor)
        require(terminalObservation.source.tableId != null && terminalObservation.source.handId != null)
        require(rulesetId == terminalObservation.rulesetId) { "Mismatched rulesets" }
        require(handRecordId == terminalObservation.stableHandKey()) { "Mismatched hand receipt ID" }
        require(verificationPolicyId.isNotBlank() && verificationPolicyId.length <= 128)
        // A real hand can finish on any street (including preflop); do not infer a showdown.
        require(listOf(sourceProofRef, handIdentityProofRef, terminalProofRef)
            .all { it.isNotBlank() && it.length <= 256 })
        require(sourceEvidence == PokerEvidence.INDEPENDENT &&
            handIdentityEvidence == PokerEvidence.INDEPENDENT && terminalEvidence == PokerEvidence.INDEPENDENT) {
            "OCR or inferred evidence cannot verify a hand"
        }
        require(heroNetChips == null || resultEvidence == PokerEvidence.INDEPENDENT) {
            "OCR-only or inferred net result cannot be a verified hand outcome"
        }
    }
}

/** Adapter is passive: it cannot dispatch game actions, authenticate, or mark OCR verified. */
interface PokerSourceAdapter<in T> {
    val sourceNamespace: String
    fun normalize(event: T): CanonicalPokerObservation
}

object PokerSourceAdapterBoundary {
    fun <T> normalize(adapter: PokerSourceAdapter<T>, event: T): CanonicalPokerObservation =
        adapter.normalize(event).also {
            require(adapter.sourceNamespace.isNotBlank() && it.source.namespace == adapter.sourceNamespace) {
                "Cross-source namespace mismatch"
            }
        }
}
