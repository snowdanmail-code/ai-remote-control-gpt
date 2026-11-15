package com.example.airemote

import org.junit.Assert.*
import org.junit.Test

class T054VerifiedOpponentOpportunitiesTest {
    private fun event(id: String, kind: T054PreflopEventKind) =
        T054VerifiedPreflopEvent(id, kind, true)
    private fun response(id: String, kind: T054FacingBetChoice) =
        T054VerifiedFacingBetDecision(id, kind, true)
    private fun transcript(
        pre: List<T054VerifiedPreflopEvent> = emptyList(),
        faced: List<T054VerifiedFacingBetDecision> = emptyList(),
        complete: Boolean = false
    ) = T054OpponentOpportunityTranscript(
        opponentKey = "verified-actor-A", identityIndependentlyVerified = true,
        sourceIndependentlyVerified = true,
        dealtInPreflopIndependentlyVerified = complete,
        completePreflopSequenceIndependentlyVerified = complete,
        preflop = pre, facingBet = faced)

    @Test fun fullHistoryCountsRealNegativeOpportunity() {
        val result = T054VerifiedOpponentOpportunities.derive(
            transcript(pre = listOf(event("a", T054PreflopEventKind.POST_BLIND),
                event("b", T054PreflopEventKind.FOLD)), complete = true))!!
        assertEquals(false, result.handFacts.vpip)
        assertEquals(false, result.handFacts.preflopRaise)
        assertTrue(result.preflopOpportunityConfirmed)
    }

    @Test fun forcedBigBlindAndAnteAreNeverVoluntary() {
        val result = T054VerifiedOpponentOpportunities.derive(
            transcript(pre = listOf(event("a", T054PreflopEventKind.ANTE),
                event("b", T054PreflopEventKind.POST_BLIND)), complete = true))!!
        assertEquals(false, result.handFacts.vpip)
        assertEquals(false, result.handFacts.preflopRaise)
    }

    @Test fun partialVerifiedFoldIsNotProofOfCompletePreflop() {
        val result = T054VerifiedOpponentOpportunities.derive(
            transcript(pre = listOf(event("a", T054PreflopEventKind.FOLD))))!!
        assertNull(result.handFacts.vpip)
        assertNull(result.handFacts.preflopRaise)
        assertFalse(result.preflopOpportunityConfirmed)
    }

    @Test fun partialVerifiedRaiseCanProvePositiveButNotOtherNegatives() {
        val result = T054VerifiedOpponentOpportunities.derive(
            transcript(pre = listOf(event("a", T054PreflopEventKind.RAISE))))!!
        assertEquals(true, result.handFacts.vpip)
        assertEquals(true, result.handFacts.preflopRaise)
    }

    @Test fun fullVerifiedCallIsVpipButNotPfr() {
        val result = T054VerifiedOpponentOpportunities.derive(
            transcript(pre = listOf(event("a", T054PreflopEventKind.CALL)),
                complete = true))!!
        assertEquals(true, result.handFacts.vpip)
        assertEquals(false, result.handFacts.preflopRaise)
    }

    @Test fun singleFacingBetCallCountsAsVerifiedNotFolding() {
        val result = T054VerifiedOpponentOpportunities.derive(
            transcript(faced = listOf(response("face-1", T054FacingBetChoice.CALL))))!!
        assertEquals(true, result.handFacts.facedBet)
        assertEquals(false, result.handFacts.foldedWhenFacingBet)
        assertEquals(1, result.verifiedFacingBetOpportunities)
        assertEquals(0, result.verifiedFoldsToBet)
    }

    @Test fun twoFacingBetDecisionsAreNotCompressedIntoOneV2Outcome() {
        val result = T054VerifiedOpponentOpportunities.derive(transcript(
            faced = listOf(response("face-1", T054FacingBetChoice.CALL),
                response("face-2", T054FacingBetChoice.FOLD))))!!
        assertEquals(2, result.verifiedFacingBetOpportunities)
        assertEquals(1, result.verifiedFoldsToBet)
        assertNull(result.handFacts.foldedWhenFacingBet)
    }

    @Test fun rejectsUnverifiedEventAndDuplicateOpportunity() {
        assertNull(T054VerifiedOpponentOpportunities.derive(
            transcript(pre = listOf(event("a", T054PreflopEventKind.RAISE)
                .copy(independentlyVerified = false)))))
        assertNull(T054VerifiedOpponentOpportunities.derive(
            transcript(faced = listOf(response("same", T054FacingBetChoice.CALL),
                response("same", T054FacingBetChoice.FOLD)))))
    }

    @Test fun rejectsCompleteHistoryWithoutDealtInProofOrProvider() {
        val original = transcript(complete = true)
        assertNull(T054VerifiedOpponentOpportunities.derive(
            original.copy(dealtInPreflopIndependentlyVerified = false)))
        assertNull(T054VerifiedOpponentOpportunities.derive(
            original.copy(sourceIndependentlyVerified = false)))
    }

    @Test fun unknownsStayUnknownWhenNoHistoryPresent() {
        val r = T054VerifiedOpponentOpportunities.derive(transcript())!!
        assertNull(r.handFacts.vpip)
        assertNull(r.handFacts.preflopRaise)
        assertNull(r.handFacts.foldedWhenFacingBet)
        assertNull(r.handFacts.facedBet)
    }
}
