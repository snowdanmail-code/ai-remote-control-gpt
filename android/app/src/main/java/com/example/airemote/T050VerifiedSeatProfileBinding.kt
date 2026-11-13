package com.example.airemote

/**
 * T050/02: only authenticated stable actor keys may link durable
 * SQLite summaries to the live screen seat. Popup nicknames, avatar color
 * and ordinal list position are never sufficient bindings.
 */
object T050VerifiedSeatProfileBinding {
    fun authorizedSeatRefs(
        occupants: List<T046OpponentSeatMemory>
    ): Map<String, Int> {
        val clean = occupants.filter {
            !it.ambiguousIdentity &&
                it.participation == T046HandParticipation.ACTIVE_CONFIRMED &&
                it.verifiedActorKey?.length in 1..128 &&
                it.seat in 0..9
        }
        // A duplicated stable actor key across two visible seats is a source
        // inconsistency: do not provide an arbitrarily selected mapping.
        val keys = clean.groupBy { requireNotNull(it.verifiedActorKey) }
            .filterValues { it.size == 1 }
        val seats = clean.groupBy { it.seat }
            .filterValues { it.size == 1 }
        return keys.mapNotNull { (actor, values) ->
            val seat = values.single().seat
            if (seats[seat]?.singleOrNull()?.verifiedActorKey == actor)
                actor to seat else null
        }.toMap()
    }

    fun eligibleProfiles(
        profiles: List<OpponentLearningSummary>,
        authorizedSeats: Map<String, Int>
    ): List<OpponentLearningSummary> =
        profiles.filter {
            it.opponentKey in authorizedSeats &&
                it.observedHands >= 1
        }.distinctBy { it.opponentKey }.sortedBy {
            authorizedSeats[it.opponentKey]
        }.take(5)
}
