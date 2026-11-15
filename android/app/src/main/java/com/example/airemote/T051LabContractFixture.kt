package com.example.airemote

/**
 * Synthetic and isolated fixture used by the in-app test button.
 * It deliberately never calls OnDeviceAutonomousRuntime.offerFrameBoundModelAdvice,
 * never writes the verified-hand database, and cannot address the 1win window.
 * Flags in this fixture describe FAKE test data, not provenance of any real hand.
 */
data class T051ContractTestResult(
    val promptCreated: Boolean,
    val accepted: Boolean,
    val reason: String
)

object T051LabContractFixture {
    fun evaluate(nowMs: Long, scenario: T051LabScenario): T051ContractTestResult {
        val viewedAt = nowMs - 20L
        val view = VerifiedLiveView(
            5L, 21L, viewedAt, "t051-synthetic-hand", "t051-test-frame",
            foregroundVerified = true, stageVerified = true,
            turnVerified = true, exactLabelsVerified = true,
            legalActions = setOf(LiveAction.CHECK)
        )
        val labTap = VerifiedTapArea(
            100, 900, 200, 950, 150, 925, 1080, 2340,
            LiveAction.CHECK, "CHECK", "com.example.airemote.testonly", 7L
        )
        val observation = AutonomousObservation(
            view, "t051-synthetic-table", "t051-synthetic-hand", "t051-turn",
            "com.example.airemote.testonly", 7L,
            tableHandIndependentlyVerified = true,
            windowIndependentlyVerified = true,
            phase = HandStage.PREFLOP,
            timerRemainingMs = 7_000L,
            potChips = 400L,
            callChips = 0L,
            availableTaps = mapOf(LiveAction.CHECK to labTap),
            selectedBetChips = 150L,
            minVerifiedBetChips = 100L,
            maxVerifiedBetChips = 200L
        )
        val cards = StableCardReading(
            nowMs - 100L, 5L, 14L, listOf("Ah", "Kd"), emptyList(), 0.99)
        val prompt = T043GptDecisionContract.buildPrompt(
            observation, cards, nowMs)
            ?: return T051ContractTestResult(false, false, "UNVERIFIED_STATE")
        // Explicitly scripted model output: this tests the real local contract
        // but NOT the network, GPT thinking, an opponent model or actual wager.
        val response = if (scenario == T051LabScenario.RAISE_REJECTED)
            """{"schema":"t043.gpt.decision.v1","action":"RAISE","amount_chips":150}"""
        else """{"schema":"t043.gpt.decision.v1","action":"CHECK","amount_chips":null}"""
        val review = T043GptDecisionContract.review(response, observation, nowMs)
        return T051ContractTestResult(
            promptCreated = prompt.isNotBlank(),
            accepted = review.accepted,
            reason = review.reason
        )
    }
}
