package com.example.airemote

object IncidentJournalFieldsScenarios {
    @JvmStatic fun main(args: Array<String>) {
        var count = 0
        fun yes(value: Boolean) { check(value); count++ }
        val detailed = IncidentFormat.encode(IncidentEvent(200, IncidentSeverity.ERROR,
            IncidentCode.DECISION_REJECTED, action = "CALL", stage = "TURN",
            potChips = 1200, callChips = 100, timerRemainingMs = 2400,
            legalActions = setOf("FOLD", "CALL", "secret:123")), 8, "run-42")
        yes(detailed.contains("\"pot_chips\":1200"))
        yes(detailed.contains("\"call_chips\":100"))
        yes(detailed.contains("\"timer_remaining_ms\":2400"))
        yes(detailed.contains("\"stage\":\"TURN\""))
        yes(detailed.contains("\"legal_actions\":[\"CALL\",\"FOLD\"]"))
        yes(!detailed.contains("secret"))
        yes(detailed.contains("\"run_id\":\"run-42\""))
        val badStage = IncidentFormat.encode(IncidentEvent(5, IncidentSeverity.INFO,
            IncidentCode.SAFETY_BLOCKED, stage = "name\":\"pwn"), 9)
        yes(!badStage.contains("stage"))
        println("IncidentJournalFields: $count checks passed")
    }
}
