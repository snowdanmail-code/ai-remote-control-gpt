package com.example.airemote

/** Synthetic turn for the operator's cloud diagnostic. Never enters the AUTO mailbox. */
internal class T055GptConnectionProbe(capturedAtMs: Long) {
    private val actions = setOf(LiveAction.CALL, LiveAction.FOLD)
    private val packageName = "com.example.airemote.diagnostic"
    private val observation = AutonomousObservation(
        view = VerifiedLiveView(1L, 1L, capturedAtMs, "diagnostic-hand", "synthetic",
            foregroundVerified = true, stageVerified = true, turnVerified = true,
            exactLabelsVerified = true, legalActions = actions),
        tableId = "diagnostic-table", handId = "diagnostic-hand", turnId = "diagnostic-turn",
        foregroundPackage = packageName, windowGeneration = 1L,
        tableHandIndependentlyVerified = true, windowIndependentlyVerified = true,
        phase = HandStage.FLOP, timerRemainingMs = 7000L, potChips = 400L, callChips = 100L,
        availableTaps = actions.associateWith { action ->
            VerifiedTapArea(100, 900, 200, 950, 150, 925, 1080, 2340,
                action, action.name, packageName, 1L)
        }
    )
    private val cards = StableCardReading(capturedAtMs, 1L, 1L,
        listOf("Ah", "Kd"), listOf("Qs", "7h", "2c"), 1.0)

    fun prompt(nowMs: Long): String? = T043GptDecisionContract.buildPrompt(
        observation, cards, nowMs)

    fun report(response: String, receivedAtMs: Long): String {
        val result = T043GptDecisionContract.review(response, observation, receivedAtMs)
        if (result.reason == "STALE_FRAME") return "Ответ получен после дедлайна кадра (1000 мс)"
        val action = result.proposal?.action ?: return "Ответ отклонён · ${result.reason}"
        return "Допустимая рекомендация · ${action.name}"
    }
}
