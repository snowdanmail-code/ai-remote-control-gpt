package com.example.airemote

import org.junit.Assert.*
import org.junit.Test

class T050VerifiedSeatProfileBindingTest {
    private fun person(
        seat: Int, id: String?,
        state: T046HandParticipation = T046HandParticipation.ACTIVE_CONFIRMED,
        ambiguous: Boolean = false
    ) = T046OpponentSeatMemory(seat, "P$seat", "NickLocal$seat",
        emptyList(), ambiguous, state, "h1", id)

    private fun profile(id: String) = OpponentLearningSummary(
        id, 42, 12, 40, 8, 40, 15, 5, 2, 10, 8, 3)

    @Test fun mapsStableVerifiedActorOnlyToCurrentActiveSeat() {
        val map = T050VerifiedSeatProfileBinding.authorizedSeatRefs(listOf(
            person(1, "stable_1"),
            person(2, null),
            person(3, "stable_3", T046HandParticipation.FOLDED_CONFIRMED),
            person(4, "stable_4", T046HandParticipation.LEFT_TABLE_CONFIRMED),
            person(5, "stable_5", ambiguous=true)))
        assertEquals(mapOf("stable_1" to 1), map)
        val profiles = T050VerifiedSeatProfileBinding.eligibleProfiles(
            listOf(profile("stable_1"),profile("stable_3"),profile("unknown")),
            map)
        assertEquals(listOf("stable_1"),profiles.map { it.opponentKey })
    }

    @Test fun duplicateStableActorKeyAcrossSeatsIsNotARealIdentityProof() {
        val map = T050VerifiedSeatProfileBinding.authorizedSeatRefs(listOf(
            person(1, "same_key"),
            person(3, "same_key"),
            person(2, "verified_ok")))
        assertEquals(mapOf("verified_ok" to 2), map)
    }

    @Test fun differentStableActorCannotInheritOldSeatStatistics() {
        val memory = T046TableOpponentMemory()
        val s = T046TableSession("com.example.game", 1L, 2L)
        assertTrue(memory.beginSession(s, 5))
        assertTrue(memory.observePopup(s,T046PopupObservation(1,"OldNick",
            listOf(OpponentPopupMetricValue(OpponentPopupMetric.VPIP,40.0)))))
        assertTrue(memory.beginVerifiedHand(s,"hand_1",true))
        assertTrue(memory.applyVerifiedEvent(s,"hand_1","act_1",1,
            T046HandParticipation.ACTIVE_CONFIRMED,
            T046Evidence.VERIFIED_HAND_EVENT,"stableOld"))
        assertEquals(mapOf("stableOld" to 1),
            T050VerifiedSeatProfileBinding.authorizedSeatRefs(memory.snapshot(s)))
        assertFalse(memory.applyVerifiedEvent(s,"hand_1","act_2",1,
            T046HandParticipation.ACTIVE_CONFIRMED,
            T046Evidence.VERIFIED_HAND_EVENT,"stableNew"))
        assertTrue(memory.snapshot(s).single().ambiguousIdentity)
        assertTrue(memory.snapshot(s).single().popupStatistics.isEmpty())
        assertTrue(T050VerifiedSeatProfileBinding.authorizedSeatRefs(
            memory.snapshot(s)).isEmpty())
    }

    @Test fun clearedSeatCanBindANewVerifiedActorAfterVacancyTransition() {
        val memory = T046TableOpponentMemory()
        val s = T046TableSession("com.example.game", 1L, 2L)
        memory.beginSession(s, 5)
        memory.observePopup(s,T046PopupObservation(1,"OldNick",emptyList()))
        memory.beginVerifiedHand(s,"hand_1",true)
        memory.applyVerifiedEvent(s,"hand_1","act_1",1,
            T046HandParticipation.ACTIVE_CONFIRMED,
            T046Evidence.VERIFIED_HAND_EVENT,"stableOld")
        assertTrue(memory.confirmedSeatTurnover(s,1,"turnover_1",true))
        assertEquals(null,memory.snapshot(s).single().verifiedActorKey)
        assertTrue(memory.applyVerifiedEvent(s,"hand_1","act_2",1,
            T046HandParticipation.ACTIVE_CONFIRMED,
            T046Evidence.VERIFIED_HAND_EVENT,"stableNew"))
        assertEquals(mapOf("stableNew" to 1),
            T050VerifiedSeatProfileBinding.authorizedSeatRefs(memory.snapshot(s)))
    }
}
