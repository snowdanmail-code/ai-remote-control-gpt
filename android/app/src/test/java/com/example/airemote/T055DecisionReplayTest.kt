package com.example.airemote

import org.junit.Assert.*
import org.junit.Test

/** T055 synthetic deterministic replay, NOT measured GPT strategy quality. */
class T055DecisionReplayTest {
    private fun tap(action: LiveAction) = VerifiedTapArea(
        100, 900, 200, 950, 150, 925, 1080, 2340,
        action, action.name, "com.example.testgame", 7L
    )
    private fun observation(
        street: HandStage,
        legal: Set<LiveAction>,
        capturedAt: Long = 1500L,
        timer: Long = 7000L
    ): AutonomousObservation {
        val view = VerifiedLiveView(5L, 21L, capturedAt,
            "hand-55", "fingerprint-21", foregroundVerified = true,
            stageVerified = true, turnVerified = true,
            exactLabelsVerified = true, legalActions = legal)
        return AutonomousObservation(view, "table-replay", "hand-55", "turn-3",
            "com.example.testgame", 7L, true, true,
            street, timer, 400L, 100L, legal.associateWith(::tap),
            selectedBetChips = 150L,
            minVerifiedBetChips = 100L,
            maxVerifiedBetChips = 200L)
    }
    private fun cards(street: HandStage) = StableCardReading(
        1400L, 5L, 14L, listOf("Ah", "Kd"),
        when (street) {
            HandStage.PREFLOP -> emptyList()
            HandStage.FLOP -> listOf("2h", "3h", "4h")
            HandStage.TURN -> listOf("2h", "3h", "4h", "5c")
            HandStage.RIVER -> listOf("2h", "3h", "4h", "5c", "6d")
            else -> emptyList()
        }, 0.99)
    private fun model(action: String, amount: String = "null") =
        """{"schema":"t043.gpt.decision.v1","action":"$action","amount_chips":$amount}"""

    @Test fun fourStreetsSendCorrectBoardSizeAndRejectWrongStage() {
        val streets = listOf(HandStage.PREFLOP, HandStage.FLOP,
            HandStage.TURN, HandStage.RIVER)
        for ((index, street) in streets.withIndex()) {
            val obs = observation(street, setOf(LiveAction.FOLD, LiveAction.CALL))
            val prompt = T043GptDecisionContract.buildPrompt(
                obs, cards(street), 2000L)
            assertNotNull("Missing prompt on " + street.name, prompt)
            assertTrue(prompt!!.contains("\"street\":\"" + street.name + "\""))
            assertTrue(prompt.contains("\"board_cards\":["))
            assertNotNull(T043GptDecisionContract.review(
                model("FOLD"), obs, 2000L).proposal)
            assertNull(T043GptDecisionContract.buildPrompt(
                obs, cards(streets[(index + 1) % 4]), 2000L))
        }
    }

    @Test fun legalRecommendationsCannotBypassActionAndSchemaGuards() {
        for (street in listOf(HandStage.PREFLOP, HandStage.FLOP,
            HandStage.TURN, HandStage.RIVER)) {
            val obs = observation(street,
                setOf(LiveAction.FOLD, LiveAction.CALL, LiveAction.RAISE))
            val ok = T043GptDecisionContract.review(model("CALL"), obs, 2000L)
            assertTrue(ok.reason, ok.accepted)
            assertEquals("ACCEPTABLE_CANDIDATE_NOT_A_GESTURE", ok.reason)
            assertEquals("NOT_LEGAL", T043GptDecisionContract.review(
                model("CHECK"), obs, 2000L).reason)
            val injected = """{"schema":"t043.gpt.decision.v1","action":"FOLD","amount_chips":null,"permission":true}"""
            assertEquals("INVALID_SCHEMA", T043GptDecisionContract.review(
                injected, obs, 2000L).reason)
        }
    }

    @Test fun raiseRequiresAlreadySelectedVerifiedAmount() {
        val obs = observation(HandStage.TURN, setOf(LiveAction.RAISE))
        val ok = T043GptDecisionContract.review(model("RAISE", "150"),obs,2000L)
        assertTrue(ok.reason,ok.accepted)
        assertEquals(150L,ok.proposal?.amountChips)
        for (amount in listOf("149","151","0","null","150.0","\"150\"")) {
            assertFalse("invalid raise amount " + amount,
                T043GptDecisionContract.review(model("RAISE",amount),obs,2000L).accepted)
        }
        assertFalse(T043GptDecisionContract.review(model("ALL_IN"),obs,2000L).accepted)
    }

    @Test fun staleTurnCannotMakeAProposal() {
        val obs = observation(HandStage.RIVER,setOf(LiveAction.FOLD,LiveAction.CALL))
        assertEquals("STALE_FRAME",T043GptDecisionContract.review(
            model("FOLD"),obs,2501L).reason)
        assertNull(T043GptDecisionContract.buildPrompt(
            obs,cards(HandStage.RIVER),2501L))
    }

    @Test fun chatGptCandidateSurvivesOnlyAnEquivalentNewerVerifiedFrame() {
        val reading = cards(HandStage.TURN)
        val first = observation(HandStage.TURN, setOf(LiveAction.CALL, LiveAction.FOLD)).copy(
            independentlyMatchedCards = VerifiedDecisionCards(reading.hole, reading.community),
            equityEvidence = FrameBoundEquity("hand-55", "turn-3", 5L, 21L, 0.5, 300, true))
        val later = first.copy(
            view = first.view.copy(frameSequence = 22L, capturedAtMs = 1800L),
            timerRemainingMs = 6700L,
            equityEvidence = first.equityEvidence!!.copy(frameSequence = 22L))
        assertNotNull(T043GptDecisionContract.buildPrompt(first, reading, 1600L))
        val wrongReading = reading.copy(hole = listOf("Ah", "Qs"))
        assertNull(T043GptDecisionContract.buildPrompt(first, wrongReading, 1600L))

        val reply = model("CALL")
        val candidate = T043GptDecisionContract.review(
            reply, first, 1900L, allowMatchingLaterFrame = true).proposal!!
        val inbox = TurnScopedProposalMailbox()
        val strategy = ValidatedPlayerStrategy(AutonomousStrategy { null }, inbox) { 1950L }
        assertTrue(inbox.offer(candidate))
        assertEquals(LiveAction.CALL, strategy.decide(later)?.action)
        assertNull(strategy.decide(later))

        val changed = listOf(
            later.copy(tableId = "other-table"),
            later.copy(turnId = "turn-4"),
            later.copy(windowIndependentlyVerified = false),
            later.copy(potChips = 401L),
            later.copy(callChips = 101L),
            later.copy(selectedBetChips = 151L),
            later.copy(independentlyMatchedCards = VerifiedDecisionCards(listOf("Ah", "Qs"), reading.community)),
            later.copy(independentlyMatchedCards = null),
            later.copy(equityEvidence = later.equityEvidence!!.copy(potShare = 0.51)),
            later.copy(view = later.view.copy(stateFingerprint = "another-state")),
            later.copy(view = later.view.copy(frameSequence = 20L)),
            later.copy(availableTaps = emptyMap()))
        for (observation in changed) {
            assertTrue(inbox.offer(candidate))
            assertNull(strategy.decide(observation))
        }
        val frameOnly = T043GptDecisionContract.review(reply, first, 1900L).proposal!!
        assertTrue(inbox.offer(frameOnly))
        assertNull(strategy.decide(later))

        val late = T043GptDecisionContract.review(
            reply, first, 2450L, allowMatchingLaterFrame = true).proposal!!
        assertNull(PlayerModelDecisionGuard.inspect(later, late, 2501L).candidate)
    }

    @Test fun cloudProbeUsesTheRealContractAndTheMeasuredDeadline() {
        val probe = T055GptConnectionProbe(1500L)
        val prompt = probe.prompt(1501L)!!
        assertTrue(prompt.contains("diagnostic-hand"))
        assertTrue(prompt.contains("\"pot_chips\":400"))
        assertTrue(prompt.contains("\"call_chips\":100"))
        assertEquals("Допустимая рекомендация · CALL", probe.report(model("CALL"), 2500L))
        assertEquals("Ответ получен после дедлайна кадра (1000 мс)",
            probe.report(model("CALL"), 2501L))
        assertEquals("Ответ отклонён · NOT_LEGAL", probe.report(model("CHECK"), 2000L))
        assertEquals("Ответ отклонён · INVALID_JSON_SHAPE", probe.report("CALL", 2000L))
        assertNull(probe.prompt(2501L))
    }

    @Test fun timelineAuditRecordsLatencyAndTurnSafetyMargins() {
        val timely = T055ReplayDeadlineAudit.assess(T055ReplayTimeline(
            1500L,1700L,1900L,7000L))
        assertTrue(timely.eligibleForContractReview)
        assertEquals(200L,timely.cloudElapsedMs)
        assertTrue(timely.explains.contains("no action authority"))
        assertEquals(T055ReplayTimingVerdict.STALE_ON_RESPONSE,
            T055ReplayDeadlineAudit.assess(T055ReplayTimeline(
                1500L,1700L,2600L,7000L)).verdict)
        assertEquals(T055ReplayTimingVerdict.INSUFFICIENT_TURN_RESERVE,
            T055ReplayDeadlineAudit.assess(T055ReplayTimeline(
                1500L,1600L,1700L,1950L)).verdict)
        assertEquals(T055ReplayTimingVerdict.INVALID_TIMESTAMPS,
            T055ReplayDeadlineAudit.assess(T055ReplayTimeline(
                1500L,1400L,1600L,7000L)).verdict)
    }

    @Test fun legalActionIsNotProofOfOptimalPokerStrategy() {
        val obs = observation(HandStage.FLOP,setOf(LiveAction.FOLD,LiveAction.CALL))
        val call = T043GptDecisionContract.review(model("CALL"),obs,2000L)
        val fold = T043GptDecisionContract.review(model("FOLD"),obs,2000L)
        assertTrue(call.accepted)
        assertTrue(fold.accepted)
        assertNotEquals(call.proposal?.action,fold.proposal?.action)
    }
}
