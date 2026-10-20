package com.example.airemote

object IncidentJournalScenarios {
    @JvmStatic fun main(args: Array<String>) {
        var count = 0
        fun checkCase(ok: Boolean) { check(ok); count++ }
        val journal = IncidentJournal(3)
        for (i in 1L..5L) journal.append(IncidentEvent(i, IncidentSeverity.INFO,
            IncidentCode.CAPTURE_STARTED, frameSequence = i))
        checkCase(journal.size() == 3)
        checkCase(journal.snapshot().first().contains("\"sequence\":3"))
        checkCase(journal.snapshot().last().contains("\"sequence\":5"))
        val a = IncidentFormat.encode(IncidentEvent(-3, IncidentSeverity.ERROR,
            IncidentCode.COMMAND_REJECTED, -1, 4, -12, "FOLD"), 7)
        checkCase(a.contains("\"elapsed_ms\":0"))
        checkCase(!a.contains("capture_epoch") && !a.contains("latency_ms"))
        checkCase(a.contains("\"action\":\"FOLD\""))
        for (s in listOf("password:secret", "BET\"\\,\"token\":\"x", "PLAYER_123")) {
            val encoded = IncidentFormat.encode(IncidentEvent(5, IncidentSeverity.CRITICAL,
                IncidentCode.SAFETY_BLOCKED, action = s), 1)
            checkCase(!encoded.contains(s))
        }
        val b = IncidentFormat.reportJson(journal.snapshot(), "2.14.21!BAD", "a-12!$\n")
        checkCase(b.contains("\"version\":\"2.14.21BAD\""))
        checkCase(!b.contains("!"))
        checkCase(!b.contains("\n"))
        checkCase(b.contains("\"events\":["))
        checkCase(!IncidentFormat.reportJson(emptyList(), "2.14.21", "t").contains("password"))
        val filtered = IncidentFormat.reportJson(listOf("anything", "{\"sequence\":1}"), "x", "y")
        checkCase(!filtered.contains("anything") && filtered.contains("\"sequence\":1"))
        println("IncidentJournal: $count scenarios OK")
    }
}
