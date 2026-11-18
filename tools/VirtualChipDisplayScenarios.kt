package com.example.airemote

object VirtualChipDisplayScenarios {
    @JvmStatic fun main(args: Array<String>) {
        var n = 0
        fun ok(value: Boolean, label: String) { check(value) { label }; n++ }
        ok(VirtualChipDisplayParser.parseLabel("Колл 0.02 $")?.hundredths == 2L,
            "fractional call")
        ok(VirtualChipDisplayParser.parseLabel("CALL 1.5 $")?.hundredths == 150L,
            "single decimal digit")
        ok(VirtualChipDisplayParser.parseLabel("Банк 42.35 $")?.hundredths == 4235L,
            "public pot value")
        ok(VirtualChipDisplayParser.parseLabel("Колл 0,02 $")?.hundredths == 2L,
            "comma decimal")
        ok(VirtualChipDisplayParser.parseLabel("Колл 0.02 $")?.kind == NumericCandidateKind.CALL_AMOUNT,
            "separate CALL numeric evidence")
        ok(VirtualChipDisplayParser.parseLabel("Колл 0.021 $") == null,
            "do not silently round")
        ok(VirtualChipDisplayParser.parseLabel("Колл -1.00 $") == null,
            "negative prohibited")
        ok(VirtualChipDisplayParser.parseLabel("Колл unknown $") == null,
            "unknown prohibited")
        ok(VirtualChipDisplayParser.parseLabel("Колл 0.02") != null,
            "plain fractional value when UI omits decorative dollar glyph")
        ok(VirtualChipDisplayParser.parseLabel("RAISE 10000000000 $") == null,
            "overflow bound")
        println("VirtualChipDisplayScenarios: $n/$n passed")
    }
}
