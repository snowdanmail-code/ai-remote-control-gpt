package com.example.airemote

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Replay-only: no app permissions, cloud calls or gestures. */
class T054GptDecisionContextTest {
    private val source = PokerSourceIdentity("fixture/study", "table-8", "session-1", "hand-91")
    private fun live(call: Long = 0L, pot: Long? = 400L): AutonomousObservation {
        val view = VerifiedLiveView(5L, 21L, 1500L, "hand-91", "fingerprint-21",
            foregroundVerified = true, stageVerified = true,
            turnVerified = true, exactLabelsVerified = true,
            legalActions = setOf(LiveAction.FOLD, LiveAction.CHECK, LiveAction.CALL))
        return AutonomousObservation(
            view, "table-8", "hand-91", "turn-3", "com.example.testgame",
            7L, tableHandIndependentlyVerified = true,
            windowIndependentlyVerified = true, phase = HandStage.PREFLOP,
            timerRemainingMs = 7000L, potChips = pot, callChips = call,
            availableTaps = emptyMap())
    }
    private fun cards() = StableCardReading(1400L, 5L, 14L,
        listOf("Ah", "Kd"), emptyList(), 0.99)
    private fun canonical(
        pot: Long? = 400L, heroStack: Long? = 1000L,
        events: List<CanonicalPokerAction> = listOf(
            CanonicalPokerAction(1, PokerStreet.PREFLOP, 2,
                PokerActionKind.RAISE, 50L, PokerEvidence.INDEPENDENT,
                "private_event_id_ignore_ABC"))
    ) = CanonicalPokerObservation(
        source = source, observationId = "independent-obs-1",
        tableCapacity = 6, rulesetId = PokerRulesets.STANDARD52_NO_LIMIT_V1,
        seats = (0 until 6).map { idx ->
            CanonicalPokerSeat(idx, "source-player-$idx", null,
                PokerEvidence.INDEPENDENT, dealtIn = true,
                activeInHand = idx <= 2,
                stackChips = when(idx) {
                    0 -> heroStack
                    1 -> 400L
                    2 -> 700L
                    else -> 0L
                })
        },
        seatingComplete = true, buttonSeat = 3, heroSeat = 0,
        street = PokerStreet.PREFLOP, boardCards = emptyList(),
        potChips = pot, actions = events,
        observationEvidence = PokerEvidence.INDEPENDENT
    )
    private fun bound(
        pot: Long? = 400L, call: Long? = 0L,
        stack: Long? = 1000L,
        stackTiming: Boolean = true,
        outstanding: Long? = null,
        events: List<CanonicalPokerAction> = canonical().actions
    ): T054GptTurnBoundContext {
        val base = canonical(pot, stack, events)
        val bets = T054IndependentBettingSnapshot(
            source = source, street = PokerStreet.PREFLOP,
            potBeforeHeroActionChips = pot, costToCallChips = call,
            bigBlindChips = 10L, currentBetToChips = outstanding ?: call,
            heroCommittedOnStreetChips = 0L,
            lastFullRaiseSizeChips = 10L,
            stacksAreRemainingBehind = stackTiming,
            evidence = PokerEvidence.INDEPENDENT
        )
        val hand = T054ReadOnlyHandContextBuilder.build(base, bets)!!
        return T054GptTurnBoundContext(hand, "turn-3", 5L, 21L, 7L, 1500L)
    }
    private fun prompt(
        observation: AutonomousObservation = live(),
        context: T054GptTurnBoundContext = bound()
    ): String? = T043GptDecisionContract.buildPrompt(
        observation, cards(), 2000L, turnContext = context)

    private fun json(prompt: String): JSONObject {
        val start = prompt.indexOf("{\"schema\":")
        require(start >= 0) { "Missing structured model packet" }
        return JSONObject(prompt.substring(start))
    }

    @Test fun verifiedReplayBindsPositionStacksSprAndEventsToCurrentTurn() {
        val prompt = prompt()
        assertNotNull(prompt)
        assertTrue(prompt!!.length <= 1600)
        val data = json(prompt)
        val study = data.getJSONObject("t054_read_only_hand")
        assertEquals("READ_ONLY_PARTIAL", study.getString("type"))
        assertEquals("UNDER_THE_GUN", study.getString("hero_pos"))
        assertEquals(2, study.getInt("active"))
        assertEquals(100.0, study.getDouble("hero_bb"), 0.00001)
        assertEquals(400L, study.getLong("min_eff_stack"))
        assertEquals(1.0, study.getDouble("min_spr"), 0.00001)
        assertEquals(2, study.getJSONArray("opponents").length())
        assertEquals(1, study.getInt("event_count"))
        assertFalse(study.getBoolean("events_complete"))
        assertEquals("UNKNOWN", study.getString("event_amounts"))
        assertEquals(1, study.getJSONArray("events").length())
        assertEquals("P", study.getJSONArray("events").getJSONArray(0).getString(1))
        assertFalse(prompt.contains("private_event_id_ignore_ABC"))
        assertFalse(prompt.contains("source-player-1"))
    }

    @Test fun differentHandTurnWindowEpochFrameOrTimeNeverEntersPrompt() {
        val good = bound()
        assertNull(prompt(context = good.copy(turnId = "other")))
        assertNull(prompt(context = good.copy(captureEpoch = 8L)))
        assertNull(prompt(context = good.copy(windowGeneration = 8L)))
        assertNull(prompt(context = good.copy(frameSequence = 20L)))
        assertNull(prompt(context = good.copy(capturedAtMs = 1499L)))
        assertNull(prompt(context = good.copy(
            hand = good.hand.copy(source = source.copy(handId = "hand-92")))))
        assertNull(prompt(context = good.copy(
            hand = good.hand.copy(street = PokerStreet.RIVER))))
        assertNull(prompt(context = good.copy(
            hand = good.hand.copy(potBeforeHeroActionChips = 399L))))
    }

    @Test fun unknownStackOrPotRemainsNullAndDoesNotUseOldGenericLabels() {
        val incomplete = bound(pot = null, stackTiming = false)
        val p = prompt(context = incomplete)!!
        val data = json(p)
        val study = data.getJSONObject("t054_read_only_hand")
        assertTrue(study.isNull("hero_bb"))
        assertTrue(study.isNull("hero_stack"))
        assertTrue(study.isNull("min_eff_stack"))
        assertTrue(study.isNull("min_spr"))
        assertTrue(data.isNull("pot_chips"))
        assertTrue(data.getJSONObject("poker_math").isNull("pot_odds_required_equity"))
    }

    @Test fun shortAllInCannotInheritUnsafeGeneralPotOdds() {
        // HERO owes 25 but has 10. The displayed 400 can include side pots
        // that this player cannot win; the study math must remain unknown.
        val short = bound(pot = 400L, call = 10L, stack = 10L, outstanding = 25L)
        assertTrue(short.hand.unknownFields.contains("ALL_IN_ELIGIBLE_POT"))
        assertNull(short.hand.potOddsRequiredEquity)
        val p = prompt(live(call = 10L), short)!!
        assertTrue(json(p).getJSONObject("poker_math").isNull("pot_odds_required_equity"))
    }

    @Test fun unverifiedIndividualActionsAreNotSentAsVerifiedHistory() {
        val unverified = canonical().actions.map {
            it.copy(evidence = PokerEvidence.OCR_ONLY)
        }
        val p = prompt(context = bound(events = unverified))!!
        val s = json(p).getJSONObject("t054_read_only_hand")
        assertEquals("MIXED", s.getString("event_evidence"))
        assertEquals(1, s.getInt("event_count"))
        assertEquals(0, s.getJSONArray("events").length())
        assertFalse(p.contains("private_event_id_ignore_ABC"))
    }

    @Test fun neverSilentlyTruncatesCurrentHandEventHistoryForCharacterLimit() {
        val longHistory = (1..105).map {
            CanonicalPokerAction(it.toLong(), PokerStreet.PREFLOP,
                it % 6, PokerActionKind.CALL, null,
                PokerEvidence.INDEPENDENT, "event-$it")
        }
        val tooLong = bound(events = longHistory)
        assertNull(prompt(context = tooLong))
    }

    @Test fun missingOrContradictoryHistoryCompletenessDeclarationIsRejected() {
        val good = bound()
        assertNull(prompt(context = good.copy(
            hand = good.hand.copy(unknownFields =
                good.hand.unknownFields - "ACTION_HISTORY_COMPLETENESS"))))
    }

    @Test fun regularChatGptContractStillWorksWithoutNewProducer() {
        val old = T043GptDecisionContract.buildPrompt(live(), cards(), 2000L)
        assertNotNull(old)
        assertFalse(old!!.contains("t054_read_only_hand"))
    }
}
