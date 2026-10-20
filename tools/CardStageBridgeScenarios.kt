import com.example.airemote.*

object CardStageBridgeScenarios {
    @JvmStatic fun main(args: Array<String>) {
        var passed = 0
        fun checkThat(ok: Boolean, message: String) { check(ok) { message }; passed++ }
        val bridge = CardToHandStageBridge(HandStateTracker())
        fun card(seq: Long, epoch: Long = 1L, at: Long = 1000L, score: Double = .98,
                 board: List<String> = emptyList()) = StableCardReading(
            at, epoch, seq, listOf("As", "Kh"), board, score)
        fun source(epoch: Long = 1L, at: Long = 1000L, actual: String = "trusted.app",
                   confirmed: Boolean = true, table: String = "table-1") = IndependentlyVerifiedTableSource(
            "trusted.app", actual, table, epoch, at, true, confirmed)
        checkThat(!bridge.observe(card(1), null, 1, 1050, false).stageConfirmed,
            "no independent package/table source must stay untrusted")
        checkThat(bridge.observe(card(2), null, 1, 1100, false).probableStage == HandStage.PREFLOP,
            "still reports purely visual probable stage")
        checkThat(!bridge.observe(card(3), source(actual="impostor"), 1, 1150, false).stageConfirmed,
            "wrong actual package")
        checkThat(!bridge.observe(card(4), source(confirmed=false), 1, 1180, false).stageConfirmed,
            "unverified table identity")
        checkThat(!bridge.observe(card(5), source(at=0L), 1, 2000, false).stageConfirmed,
            "stale source")
        checkThat(!bridge.observe(card(6, at=3000L), source(at=3000L), 1, 3020, false).stageConfirmed,
            "first valid observation alone cannot confirm")
        checkThat(bridge.observe(card(7, at=3400L), source(at=3400L), 1, 3420, false).stageConfirmed,
            "two independent matching verified observations confirm")
        checkThat(!bridge.observe(card(7, at=3400L), source(at=3400L), 1, 3420, false).stageConfirmed,
            "duplicate sequence invalidates trust")
        checkThat(!bridge.observe(card(8, at=3600L), source(at=3600L), 1, 3610, true).stageConfirmed,
            "operator STOP invalidates confirmed phase")
        checkThat(!bridge.observe(card(9, epoch=2, at=4100L), source(epoch=2, at=4100L), 2, 4110, false).stageConfirmed,
            "new capture requires reconfirmation")
        checkThat(bridge.observe(card(10, epoch=2, at=4500L), source(epoch=2, at=4500L), 2, 4510, false).stageConfirmed,
            "fresh new capture succeeds after second verified frame")
        checkThat(!bridge.observe(card(11, epoch=2, at=4800L, score=.84),
            source(epoch=2, at=4800L), 2, 4810, false).stageConfirmed,
            "low confidence passes visual detection but not trusted state")
        checkThat(!bridge.observe(card(12, epoch=2, at=5000L, board=listOf("7s", "7s", "5d")),
            source(epoch=2, at=5000L), 2, 5010, false).stageConfirmed,
            "duplicate cards reject")
        println("PASS: $passed card-to-hand bridge scenarios")
    }
}
