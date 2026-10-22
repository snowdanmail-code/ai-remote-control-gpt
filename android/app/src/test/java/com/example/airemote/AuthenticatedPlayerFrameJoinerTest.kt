package com.example.airemote

import org.junit.Assert.*
import org.junit.Test

/** Synthetic authenticated provider (not a real 1win interface).
 * Covers recognition -> independent state -> decision and tampering. */
class AuthenticatedPlayerFrameJoinerTest {
    private val source = "com.example.virtualgame"
    private val proof = ExternalTurnAttestation("example-auth-provider", "proof-13",
        "table-a", "hand-a", "turn-a", source, 7L, 5L, 13L, 1000L, 2200L)
    private val frame = FrameInfo(byteArrayOf(1,2,3), 13L, 443, 960,
        75, 0, 1000L, 7L, source, 5L)
    private val stable = StableCardReading(900L, 7L, 11L,
        listOf("Ah","Kd"),listOf("3c","9s","Jh"), .93)
    private val controls = ActionStripReport(ActionStripStatus.STABLE_DIAGNOSTIC,
        "two OCR frames", 13L, stable = listOf(
            ActionStripCue(HeroControlLane.FOLD, ExactActionCandidate.FOLD,
                NormalizedTextBox(32, 933, 300, 969), "Пас"),
            ActionStripCue(HeroControlLane.CHECK_CALL, ExactActionCandidate.CHECK,
                NormalizedTextBox(348, 933, 648, 969), "Чек")))
    private val data = IndependentlyAuthenticatedPokerFrame(
        proof, HandStage.FLOP, true, setOf(LiveAction.FOLD, LiveAction.CHECK),
        120L, 0L, 5500L, stable.hole, stable.community, "fp-auth-13")
    private val viewport = CaptureViewport(443,960,1080,2340,0,0,1080,2340)
    private val adapter = AuthenticatedPlayerFrameJoiner(
        ExternalAttestationVerifier { it.providerId == "example-auth-provider" &&
            it.proofId == "proof-13" })

    @Test fun sourceAndImageMustMatchBeforeLocalStrategyMayDecide() {
        assertTrue(viewport.valid()) // rounded real 960px compressed JPEG
        val result=adapter.combine(frame,stable,controls,data,viewport,1100L,false)
        assertTrue(result.error, result.accepted)
        val obs=requireNotNull(result.observation)
        assertEquals("hand-a", obs.handId)
        assertEquals(2,obs.availableTaps.size)
        assertEquals(LiveAction.CHECK, VerifiedLocalPokerStrategy().decide(obs)?.action)
        assertTrue(obs.tableHandIndependentlyVerified)
        assertEquals(0L,obs.callChips)
    }

    @Test fun unsignedOrWrongHandOrWrongCardsNeverPromotesOCR() {
        val bad=AuthenticatedPlayerFrameJoiner(ExternalAttestationVerifier { false })
        assertFalse(bad.combine(frame,stable,controls,data,viewport,1100L,false).accepted)
        assertFalse(adapter.combine(frame,stable,controls,
            data.copy(ownHoleCards=listOf("Ah","Ks")),viewport,1100L,false).accepted)
        assertFalse(adapter.combine(frame,stable,controls,
            data.copy(attestation=proof.copy(handId="other")),viewport,1100L,false)
            .accepted)
    }

    @Test fun impossibleOrStaleOrUnauthorizedActionsFailClosed() {
        assertFalse(adapter.combine(frame,stable,controls,data.copy(callHundredths=5),
            viewport,1100L,false).accepted)
        assertFalse(adapter.combine(frame,stable,controls,data,
            viewport,1100L,true).accepted)
        assertFalse(adapter.combine(frame,stable,controls,data,
            viewport,2300L,false).accepted)
        assertFalse(adapter.combine(frame,stable,controls,
            data.copy(legalActions=setOf(LiveAction.CALL)),viewport,1100L,false).accepted)
        assertFalse(adapter.combine(frame,stable.copy(community=listOf("Ah","3c","9s")),
            controls,data,viewport,1100L,false).accepted)
        assertFalse(adapter.combine(frame,stable,controls,data, viewport.copy(
            contentWidth=900),1100L,false).accepted)
    }

    @Test fun noBetOrRaiseWithoutAlreadyVerifiedSelectedRange() {
        val raise=controls.copy(stable=controls.stable + ActionStripCue(
            HeroControlLane.BET_RAISE,ExactActionCandidate.BET,
            NormalizedTextBox(686,933,935,969),"Ставка"))
        val packet=data.copy(legalActions=data.legalActions+LiveAction.BET,
            selectedBetHundredths=null)
        assertFalse(adapter.combine(frame,stable,raise,packet,viewport,1100L,false).accepted)
        val verified=packet.copy(selectedBetHundredths=60,minBetHundredths=40,
            maxBetHundredths=100)
        assertTrue(adapter.combine(frame,stable,raise,verified,viewport,1100L,false).accepted)
    }
}
