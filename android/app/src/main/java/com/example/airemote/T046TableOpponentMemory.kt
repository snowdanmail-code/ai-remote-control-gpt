package com.example.airemote

/**
 * Table memory does NOT equate seatIndex, OCR nickname or avatar with a
 * verified long-term player identity. All keys are scoped to one Android
 * table/capture session; persistent SQLite only gets identity-verified hands.
 */
data class T046TableSession(
    val expectedPackage: String,
    val captureEpoch: Long,
    val windowGeneration: Long
) {
    fun valid(): Boolean = expectedPackage.isNotBlank() &&
        captureEpoch >= 0L && windowGeneration >= 0L
}

enum class T046HandParticipation {
    UNKNOWN, ACTIVE_CONFIRMED, FOLDED_CONFIRMED, LEFT_TABLE_CONFIRMED
}

enum class T046Evidence {
    VERIFIED_HAND_EVENT, VERIFIED_TABLE_EXIT, OCR_ONLY
}

data class T046PopupObservation(
    val seatIndex: Int,
    val displayedNickname: String?,
    val metrics: List<OpponentPopupMetricValue>
)

data class T046OpponentSeatMemory(
    val seat: Int,
    val sessionAlias: String,
    /** Local only: never upload nickname to ChatGPT. */
    val nickname: String?,
    val popupStatistics: List<OpponentPopupMetricValue>,
    val ambiguousIdentity: Boolean,
    val participation: T046HandParticipation,
    val lastVerifiedHand: String?
)

/**
 * All methods accept only scoped, source-confirmed state. An OCR fold label
 * is not a confirmed game event. A fold never means a player LEFT THE TABLE.
 */
class T046TableOpponentMemory {
    private var session: T046TableSession? = null
    private var heroSeat = -1
    private var sequence = 0
    private var handKey: String? = null
    private val occupants = linkedMapOf<Int, T046OpponentSeatMemory>()
    private val appliedEvents = mutableSetOf<String>()
    private val safeName = Regex("""[\p{L}\p{N}_.-]{2,24}""")

    @Synchronized fun reset() {
        session = null
        heroSeat = -1
        sequence = 0
        handKey = null
        occupants.clear()
        appliedEvents.clear()
    }

    @Synchronized fun beginSession(key: T046TableSession,
                                    ownSeat: Int): Boolean {
        if (!key.valid() || ownSeat !in 0..9) return false
        if (session == key && heroSeat == ownSeat) return true
        reset()
        session = key
        heroSeat = ownSeat
        return true
    }

    private fun normalizeName(name: String?): String? =
        name?.trim()?.takeIf { safeName.matches(it) }

    /** From an avatar popup observed TWICE for the selected screen seat. */
    @Synchronized fun observePopup(key: T046TableSession,
                                    profile: T046PopupObservation): Boolean {
        if (session != key || profile.seatIndex !in 0..9 ||
            profile.seatIndex == heroSeat ||
            profile.metrics.any { !it.amount.isFinite() || it.amount < 0.0 })
            return false
        val name = normalizeName(profile.displayedNickname)
        val old = occupants[profile.seatIndex]
        if (old == null) {
            sequence++
            occupants[profile.seatIndex] = T046OpponentSeatMemory(
                profile.seatIndex, "P" + sequence,
                name, profile.metrics.toList(), false,
                T046HandParticipation.UNKNOWN, handKey)
        } else {
            // A different nickname in the same seat might be OCR drift OR a
            // replacement player. Never merge their statistics by guessing.
            val conflict = old.nickname != null && name != null &&
                !old.nickname.equals(name, ignoreCase = true)
            occupants[profile.seatIndex] = if (conflict)
                old.copy(nickname = null, popupStatistics = emptyList(),
                    ambiguousIdentity = true,
                    participation = T046HandParticipation.UNKNOWN)
            else if (old.ambiguousIdentity) old
            else old.copy(nickname = old.nickname ?: name,
                popupStatistics = profile.metrics.toList())
        }
        return true
    }

    /** Starting a new hand does NOT mean every seated player is in that hand. */
    @Synchronized fun beginVerifiedHand(key: T046TableSession,
                                         verifiedHandId: String,
                                         independentlyVerified: Boolean): Boolean {
        if (!independentlyVerified || session != key ||
            verifiedHandId.isBlank() || verifiedHandId.length > 96 ||
            verifiedHandId == handKey) return false
        handKey = verifiedHandId
        appliedEvents.clear()
        occupants.replaceAll { _, old ->
            old.copy(participation =
                if (old.participation == T046HandParticipation.LEFT_TABLE_CONFIRMED)
                    T046HandParticipation.LEFT_TABLE_CONFIRMED
                else T046HandParticipation.UNKNOWN,
                lastVerifiedHand = verifiedHandId)
        }
        return true
    }

    /** Call this only from a separately verified game-action/occupancy source. */
    @Synchronized fun applyVerifiedEvent(
        key: T046TableSession,
        verifiedHandId: String,
        eventId: String,
        seat: Int,
        next: T046HandParticipation,
        evidence: T046Evidence
    ): Boolean {
        if (session != key || verifiedHandId != handKey ||
            eventId.isBlank() || eventId.length > 96 ||
            !Regex("""[A-Za-z0-9_.-]{1,96}""").matches(eventId) ||
            evidence == T046Evidence.OCR_ONLY ||
            seat == heroSeat ||
            next == T046HandParticipation.UNKNOWN) return false
        val existing = occupants[seat] ?: return false
        if (existing.ambiguousIdentity ||
            (next == T046HandParticipation.LEFT_TABLE_CONFIRMED &&
                evidence != T046Evidence.VERIFIED_TABLE_EXIT) ||
            (next != T046HandParticipation.LEFT_TABLE_CONFIRMED &&
                evidence != T046Evidence.VERIFIED_HAND_EVENT) ||
            existing.participation == T046HandParticipation.LEFT_TABLE_CONFIRMED ||
            (existing.participation == T046HandParticipation.FOLDED_CONFIRMED &&
                existing.lastVerifiedHand == verifiedHandId) ||
            !appliedEvents.add(eventId)) return false
        occupants[seat] = existing.copy(participation = next,
            lastVerifiedHand = verifiedHandId)
        return true
    }

    /**
     * After a trusted vacancy->reoccupation transition at this seat,
     * all historical OCR metrics and the old local nickname are discarded.
     * This is never invoked for a missing avatar or a single OCR change.
     */
    @Synchronized fun confirmedSeatTurnover(
        key: T046TableSession, seat: Int,
        eventId: String, vacancyAndArrivalIndependentlyVerified: Boolean
    ): Boolean {
        if (session != key || !vacancyAndArrivalIndependentlyVerified ||
            seat == heroSeat || seat !in 0..9 ||
            eventId.isBlank() ||
            !Regex("""[A-Za-z0-9_.-]{1,96}""").matches(eventId) ||
            occupants[seat] == null ||
            !appliedEvents.add("replacement_" + eventId)) return false
        sequence++
        occupants[seat] = T046OpponentSeatMemory(
            seat, "P" + sequence, null, emptyList(), false,
            T046HandParticipation.UNKNOWN, handKey)
        return true
    }

    @Synchronized fun snapshot(key: T046TableSession): List<T046OpponentSeatMemory> =
        if (key != session) emptyList()
        else occupants.values.sortedBy { it.seat }.toList()

    @Synchronized fun summaryForChatGpt(key: T046TableSession,
                                         verifiedHandId: String): String {
        if (key != session || verifiedHandId != handKey) return ""
        val seats = occupants.values.sortedBy { it.seat }
        if (seats.isEmpty()) return ""
        fun refs(state: T046HandParticipation): String =
            seats.filter { !it.ambiguousIdentity && it.participation == state }
                .joinToString(",") { "seat_" + it.seat }
        // Never claim an opponent has folded or left from a missing avatar.
        // Unknown remains explicit rather than treated as ACTIVE.
        val uncertain = seats.filter {
            it.ambiguousIdentity ||
                it.participation == T046HandParticipation.UNKNOWN
        }
        val report = "active=" + refs(T046HandParticipation.ACTIVE_CONFIRMED) +
            ";folded=" + refs(T046HandParticipation.FOLDED_CONFIRMED) +
            ";left=" + refs(T046HandParticipation.LEFT_TABLE_CONFIRMED) +
            ";unknown=" + uncertain.size +
            // Profiles never prove that every seat was inspected.
            ";roster_unverified=1"
        val withSeatDetails = report + ";unknown_seats=" +
            uncertain.joinToString(",") { "seat_" + it.seat }
        return if (withSeatDetails.length <= 160) withSeatDetails
            else report.takeIf { it.length <= 160 } ?: ""
    }
}
