package com.example.airemote

import org.junit.Assert.*
import org.junit.Test

class T054ReadOnlyHandContextTest {
    private val source = PokerSourceIdentity("fixture/poker-study", "table-1", "session-1", "hand-1")
    private fun seats(capacity: Int = 6): List<CanonicalPokerSeat> =
        (0 until capacity).map { index ->
            CanonicalPokerSeat(
                seatIndex = index, participantId = "source-actor-$index",
                identityEvidence = PokerEvidence.INDEPENDENT,
                dealtIn = true, activeInHand = index in setOf(0, 1, 2),
                stackChips = when(index) { 0 -> 1_000L; 1 -> 400L; 2 -> 700L; else -> 0L }
            )
        }
    private fun observation(capacity: Int = 6) = CanonicalPokerObservation(
        source = source, observationId = "frame-10", tableCapacity = capacity,
        rulesetId = PokerRulesets.STANDARD52_NO_LIMIT_V1,
        seats = seats(capacity), seatingComplete = true,
        heroSeat = 0, buttonSeat = 3, street = PokerStreet.FLOP,
        boardCards = listOf(
            CanonicalPokerCard("A", "H"), CanonicalPokerCard("K", "D"),
            CanonicalPokerCard("2", "S")
        ), potChips = 100L, observationEvidence = PokerEvidence.INDEPENDENT,
        actions = listOf(
            CanonicalPokerAction(1, PokerStreet.PREFLOP, 2, PokerActionKind.RAISE,
                50, PokerEvidence.INDEPENDENT, "evt-1"),
            CanonicalPokerAction(2, PokerStreet.FLOP, 1, PokerActionKind.BET,
                25, PokerEvidence.INDEPENDENT, "evt-2")
        )
    )
    private fun betting() = T054IndependentBettingSnapshot(
        source = source, street = PokerStreet.FLOP,
        potBeforeHeroActionChips = 100L, costToCallChips = 25L,
        bigBlindChips = 10L, currentBetToChips = 100L,
        heroCommittedOnStreetChips = 75L, lastFullRaiseSizeChips = 50L,
        evidence = PokerEvidence.INDEPENDENT
    )

    @Test fun derivesMultiwayEffectiveStacksAndSprPerOpponent() {
        val result = T054ReadOnlyHandContextBuilder.build(observation(), betting())!!
        assertEquals(PokerSeatPosition.UNDER_THE_GUN, result.heroPosition)
        assertEquals(2, result.activeOpponents)
        assertEquals(true, result.isMultiway)
        assertEquals(1000L, result.heroRemainingStackChips)
        assertEquals(100.0, result.heroRemainingStackBb!!, 0.00001)
        assertEquals(400L, result.minimumEffectiveRemainingStackChips)
        assertEquals(4.0, result.minimumEffectiveRemainingSpr!!, 0.00001)
        assertEquals(0.2, result.potOddsRequiredEquity!!, 0.00001)
        assertEquals(listOf(1, 2), result.opponents.map { it.seatIndex })
        assertEquals(listOf(4.0, 7.0), result.opponents.map { it.spr })
        assertEquals(150L, result.mathematicalRaiseBounds!!.fullRaiseMinimumTo)
        assertEquals(1075L, result.mathematicalRaiseBounds.maximumTo)
        assertEquals(listOf("evt-1", "evt-2"), result.history.map { it.sourceEventId })
        assertTrue(result.history.all { !it.amountSemanticsVerified })
        assertTrue("raise amount meaning must stay unknown",
            result.unknownFields.contains("HISTORY_AMOUNT_SEMANTICS"))
    }

    @Test fun missingOpponentStackCannotProduceFakeMinimumOrSpr() {
        val modified = observation().copy(seats = seats().map {
            if (it.seatIndex == 1) it.copy(stackChips = null) else it
        })
        val result = T054ReadOnlyHandContextBuilder.build(modified, betting())!!
        assertNull(result.minimumEffectiveRemainingStackChips)
        assertNull(result.minimumEffectiveRemainingSpr)
        assertTrue(result.unknownFields.contains("ACTIVE_OPPONENT_STACK"))
        assertNull(result.opponents.single { it.seatIndex == 1 }.effectiveRemainingStackChips)
        assertEquals(7.0, result.opponents.single { it.seatIndex == 2 }.spr!!, 0.00001)
    }

    @Test fun incompleteSeatRosterCannotInventPositionsOrActiveCount() {
        val result = T054ReadOnlyHandContextBuilder.build(
            observation().copy(seatingComplete = false), betting())!!
        assertNull(result.heroPosition)
        assertNull(result.activeOpponents)
        assertNull(result.isMultiway)
        assertNull(result.minimumEffectiveRemainingStackChips)
        assertTrue(result.unknownFields.contains("HERO_POSITION"))
        assertTrue(result.unknownFields.contains("ACTIVE_ROSTER"))
    }

    @Test fun matchesIndependentTableHandStreetAndPotEvidenceExactly() {
        val baseline = observation()
        assertNull(T054ReadOnlyHandContextBuilder.build(
            baseline, betting().copy(source = source.copy(handId = "other-hand"))))
        assertNull(T054ReadOnlyHandContextBuilder.build(
            baseline, betting().copy(street = PokerStreet.TURN)))
        assertNull(T054ReadOnlyHandContextBuilder.build(
            baseline, betting().copy(potBeforeHeroActionChips = 90L)))
        assertNull(T054ReadOnlyHandContextBuilder.build(
            baseline.copy(observationEvidence = PokerEvidence.OCR_ONLY), betting()))
        assertNull(T054ReadOnlyHandContextBuilder.build(
            baseline, betting().copy(evidence = PokerEvidence.OCR_ONLY)))
    }

    @Test fun rejectsImpossibleCallCostAndWrongBoardForStreet() {
        assertNull(T054ReadOnlyHandContextBuilder.build(
            observation(), betting().copy(costToCallChips = 26L)))
        assertNull(T054ReadOnlyHandContextBuilder.build(
            observation(), betting().copy(lastFullRaiseSizeChips = 0L)))
        assertNull(T054ReadOnlyHandContextBuilder.build(
            observation().copy(street = PokerStreet.TURN), betting().copy(street = PokerStreet.TURN)))
    }

    @Test fun unknownAmountsRemainUnknownAndAreNeverInferredFromActions() {
        val result = T054ReadOnlyHandContextBuilder.build(
            observation().copy(potChips = null, boardCards = null),
            betting().copy(potBeforeHeroActionChips = null,
                costToCallChips = null, bigBlindChips = null,
                currentBetToChips = null, heroCommittedOnStreetChips = null,
                lastFullRaiseSizeChips = null))!!
        assertNull(result.potOddsRequiredEquity)
        assertNull(result.heroRemainingStackBb)
        assertNull(result.minimumEffectiveRemainingSpr)
        assertNull(result.mathematicalRaiseBounds)
        assertTrue(result.unknownFields.contains("POT"))
        assertTrue(result.unknownFields.contains("CALL_COST"))
        assertTrue(result.unknownFields.contains("BOARD"))
        assertEquals(2, result.history.size)
    }

    @Test fun handlesNineSeatCanonicalModelButDoesNotAuthorizeNineMaxUi() {
        val result = T054ReadOnlyHandContextBuilder.build(observation(9), betting())!!
        assertEquals(PokerSeatPosition.UNDER_THE_GUN, result.heroPosition)
        assertEquals(2, result.activeOpponents)
        assertTrue(result.diagnosticLine().startsWith("T054_READ_ONLY"))
    }

    @Test fun zeroPotDoesNotPretendToHaveSpr() {
        val result = T054ReadOnlyHandContextBuilder.build(
            observation().copy(potChips = 0L), betting().copy(
                potBeforeHeroActionChips = 0L))!!
        assertNull(result.minimumEffectiveRemainingSpr)
        assertTrue(result.unknownFields.contains("SPR_ZERO_POT"))
    }
}
