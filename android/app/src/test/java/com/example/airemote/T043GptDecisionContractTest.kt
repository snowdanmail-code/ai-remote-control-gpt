package com.example.airemote

import org.junit.Assert.*
import org.junit.Test

class T043GptDecisionContractTest {
    private fun cards(hole: List<String> = listOf("Ah", "Kd"),
                      board: List<String> = emptyList()) =
        StableCardReading(1_400L, 5L, 14L, hole, board, 0.99)

    private fun tap(action: LiveAction) = VerifiedTapArea(
        100, 900, 200, 950, 150, 925, 1080, 2340,
        action, action.name, "com.example.testgame", 7L
    )

    private fun observation(
        frameAt: Long = 1_500L,
        stage: HandStage = HandStage.PREFLOP,
        legal: Set<LiveAction> = setOf(
            LiveAction.FOLD, LiveAction.CHECK, LiveAction.CALL, LiveAction.RAISE),
        trusted: Boolean = true
    ): AutonomousObservation {
        val view = VerifiedLiveView(
            5L, 21L, frameAt, "hand-91", "fingerprint-21",
            foregroundVerified = trusted, stageVerified = true,
            turnVerified = true, exactLabelsVerified = true,
            legalActions = legal
        )
        return AutonomousObservation(
            view, "table-8", "hand-91", "turn-3", "com.example.testgame",
            7L, tableHandIndependentlyVerified = trusted,
            windowIndependentlyVerified = true,
            phase = stage, timerRemainingMs = 7_000L,
            potChips = 400L, callChips = 0L,
            availableTaps = legal.associateWith(::tap),
            selectedBetChips = 150L,
            minVerifiedBetChips = 100L,
            maxVerifiedBetChips = 200L
        )
    }

    private fun answer(action: String, amount: String = "null") =
        """{"schema":"t043.gpt.decision.v1","action":"$action","amount_chips":$amount}"""

    @Test fun realHoleCardsAreIncludedOnlyOnVerifiedCurrentTurn() {
        val prompt = T043GptDecisionContract.buildPrompt(observation(), cards(), 2_000L)
        assertNotNull(prompt)
        assertTrue(prompt!!.contains("\\"hole_cards\\":[\\"Ah\\",\\"Kd\\"]"))
        assertTrue(prompt.contains("\\"street\\":\\"PREFLOP\\""))
        assertTrue(prompt.contains("RECOMMENDATION_ONLY"))
        assertTrue(prompt.length <= 1_600)
    }

    @Test fun staleCardsOrUntrustedTurnCannotBuildPrompt() {
        assertNull(T043GptDecisionContract.buildPrompt(observation(trusted = false), cards(), 2_000L))
        assertNull(T043GptDecisionContract.buildPrompt(observation(), cards(), 3_000L))
        assertNull(T043GptDecisionContract.buildPrompt(observation(), cards(listOf("Ah", "Ah")), 2_000L))
        assertNull(T043GptDecisionContract.buildPrompt(observation(stage = HandStage.FLOP), cards(), 2_000L))
    }

    @Test fun validCheckIsOnlyAProposalNotAGesture() {
        val reviewed = T043GptDecisionContract.review(answer("CHECK"),
            observation(), 2_000L)
        assertTrue(reviewed.reason, reviewed.accepted)
        assertEquals(LiveAction.CHECK, reviewed.proposal?.action)
        assertEquals("hand-91", reviewed.proposal?.handId)
        assertEquals(21L, reviewed.proposal?.frameSequence)
        assertEquals("ACCEPTABLE_CANDIDATE_NOT_A_GESTURE", reviewed.reason)
    }

    @Test fun verifiedSelectedRaiseAmountIsRequired() {
        val ok = T043GptDecisionContract.review(answer("RAISE", "150"), observation(), 2_000L)
        assertTrue(ok.reason, ok.accepted)
        assertEquals(150L, ok.proposal?.amountChips)
        for (amount in listOf("149", "201", "null", "150.0", "\\"150\\"")) {
            val rejected = T043GptDecisionContract.review(answer("RAISE", amount),
                observation(), 2_000L)
            assertFalse("Should reject $amount", rejected.accepted)
        }
    }

    @Test fun modelCannotInventGesturePermissionOrOtherParameters() {
        val injected = """{"schema":"t043.gpt.decision.v1","action":"CHECK","amount_chips":null,"permission":true}"""
        assertEquals("INVALID_SCHEMA",
            T043GptDecisionContract.review(injected, observation(), 2_000L).reason)
        assertEquals("UNSUPPORTED_ACTION",
            T043GptDecisionContract.review(answer("ALL_IN"), observation(), 2_000L).reason)
        assertFalse(T043GptDecisionContract.review(answer("FOLD", "5"),
            observation(), 2_000L).accepted)
        assertFalse(T043GptDecisionContract.review(" ${answer("CHECK")}",
            observation(), 2_000L).accepted)
    }

    @Test fun unknownStaleAndIllegalDecisionsFailClosed() {
        assertFalse(T043GptDecisionContract.review(answer("CALL"), observation(), 4_000L).accepted)
        assertEquals("NOT_LEGAL",
            T043GptDecisionContract.review(answer("CALL"),
                observation(legal = setOf(LiveAction.CHECK)), 2_000L).reason)
        assertFalse(T043GptDecisionContract.review(answer("CHECK"),
            observation(trusted = false), 2_000L).accepted)
    }
}
