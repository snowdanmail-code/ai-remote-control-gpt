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
        outstanding: Long? = call,
        events: List<CanonicalPokerAction> = canonical().actions,
        contributions: Map<Int, Long>? = null
    ): T054GptTurnBoundContext {
        val base = canonical(pot, stack, events)
        val bets = T054IndependentBettingSnapshot(
            source = source, street = PokerStreet.PREFLOP,
            potBeforeHeroActionChips = pot, costToCallChips = call,
            bigBlindChips = 10L, currentBetToChips = outstanding,
            heroCommittedOnStreetChips = 0L,
            lastFullRaiseSizeChips = 10L,
            stacksAreRemainingBehind = stackTiming,
            evidence = PokerEvidence.INDEPENDENT,
            contributionsBeforeCallBySeat = contributions
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
        val boundary = prompt.indexOf("never permission to tap.")
        val start = if (boundary < 0) -1 else prompt.indexOf('{', boundary)
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
        val possiblyShort = bound(pot = 400L, call = 10L, stack = 10L, outstanding = null)
        for (context in listOf(short, possiblyShort)) {
            val packet = json(prompt(live(call = 10L), context)!!)
            assertTrue(packet.isNull("pot_chips"))
            assertEquals(10L, packet.getLong("call_chips"))
            assertTrue(packet.getJSONObject("poker_math").isNull("pot_odds_required_equity"))
        }
    }

    private fun sidePotContext() = bound(pot = 350L, call = 50L, stack = 50L, outstanding = 100L,
        contributions = mapOf(0 to 50L, 1 to 150L, 2 to 150L, 3 to 0L, 4 to 0L, 5 to 0L))

    @Test fun confirmedContributionsPriceOnlyTheHeroEligiblePot() {
        val observation = live(call = 50L, pot = 350L)
        val context = sidePotContext()
        val packet = json(prompt(observation, context)!!)
        assertEquals(250L, packet.getLong("pot_chips"))
        assertEquals(50L, packet.getLong("call_chips"))
        assertEquals(1.0 / 6.0,
            packet.getJSONObject("poker_math").getDouble("pot_odds_required_equity"), 0.00001)
        assertNull(prompt(observation, context.copy(
            hand = context.hand.copy(heroEligiblePotBeforeCallChips = 351L))))
        assertNull(prompt(observation, context.copy(
            hand = context.hand.copy(potOddsRequiredEquity = 0.125))))
    }

    @Test fun sidePotReplayComparesCallAndFoldThenChecksTheResponseDeadline() {
        val base = live(call = 50L, pot = 350L)
        val taps = setOf(LiveAction.CALL, LiveAction.FOLD).associateWith { action ->
            VerifiedTapArea(100, 900, 200, 950, 150, 925, 1080, 2340, action, action.name,
                base.foregroundPackage, base.windowGeneration)
        }
        val context = sidePotContext()
        val requestAt = 2000L
        val responseAt = 2400L
        val lateResponseAt = 2501L
        fun timeline(observation: AutonomousObservation, receivedAt: Long) =
            T055ReplayDeadlineAudit.assess(T055ReplayTimeline(
                observation.view.capturedAtMs, requestAt, receivedAt, observation.timerRemainingMs))

        // The test assigns pot shares to isolate the decision boundary.
        // FOLD has zero incremental value; CALL assumes immediate showdown.
        for ((equity, expected) in listOf(0.15 to LiveAction.FOLD, 0.20 to LiveAction.CALL)) {
            val observation = base.copy(availableTaps = taps, equityEvidence = FrameBoundEquity(
                base.handId, base.turnId, base.view.captureEpoch, base.view.frameSequence,
                equity, independentSampleCount = 100, externallyVerified = true,
                exactEnumeration = true, provenance = "SYNTHETIC_REPLAY"))
            val math = json(prompt(observation, context)!!).getJSONObject("poker_math")
            val modelEquity = math.getDouble("verified_pot_share_equity")
            val required = math.getDouble("pot_odds_required_equity")
            assertTrue(modelEquity > PokerMath.potOddsRequiredEquity(350L, 50L))
            val benchmark = if (modelEquity >= required) LiveAction.CALL else LiveAction.FOLD
            assertEquals(expected, benchmark)

            val stubResponse = JSONObject().put("schema", T043GptDecisionContract.SCHEMA)
                .put("action", benchmark.name).put("amount_chips", JSONObject.NULL).toString()
            assertTrue(timeline(observation, responseAt).eligibleForContractReview)
            val timely = T043GptDecisionContract.review(stubResponse, observation, responseAt)
            assertEquals(expected, timely.proposal?.action)
            assertEquals("ACCEPTABLE_CANDIDATE_NOT_A_GESTURE", timely.reason)
            assertEquals(T055ReplayTimingVerdict.STALE_ON_RESPONSE,
                timeline(observation, lateResponseAt).verdict)
            val stale = T043GptDecisionContract.review(stubResponse, observation, lateResponseAt)
            assertNull(stale.proposal)
            assertEquals("STALE_FRAME", stale.reason)
        }
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

    @Test fun futureStreetIsRejectedEvenIfAnEnvelopeHasMatchingFrameIds() {
        val good = bound()
        val futureAction = good.hand.history.single().copy(street = PokerStreet.FLOP)
        val futureHistory = good.copy(hand = good.hand.copy(history = listOf(futureAction)))
        assertNull(prompt(context = futureHistory))
    }

    @Test fun regularChatGptContractStillWorksWithoutNewProducer() {
        val old = T043GptDecisionContract.buildPrompt(live(), cards(), 2000L)
        assertNotNull(old)
        assertFalse(old!!.contains("t054_read_only_hand"))
    }
}
