package com.example.airemote

import org.junit.Assert.*
import org.junit.Test

/** Android Gradle unit-test entrypoint, independent of CLI scenarios. */
class PokerMathJvmTest {
    private fun cards(value: String) = value.split(" ").map(PokerCard::parse)

    @Test fun wheelAndStraightFlush() {
        assertEquals(PokerHandValue(PokerHandClass.STRAIGHT_FLUSH, listOf(5)),
            PokerMath.evaluate(cards("As 2s 3s 4s 5s Kc Qd")))
        assertTrue(PokerMath.evaluate(cards("2c 3d 4h 5s 6d Kc Qd")) >
            PokerMath.evaluate(cards("As 2s 3d 4h 5c Kc Qd")))
    }

    @Test fun duplicatePhysicalCardRejected() {
        try {
            PokerMath.evaluate(cards("As As 2s 3d 4c"))
            fail("Duplicate cards must not yield a rank")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun sidePotsAndUncalledBet() {
        val result = PokerMath.sidePots(mapOf("A" to 100L, "B" to 200L, "C" to 300L), emptySet())
        assertEquals(listOf(300L, 200L), result.pots.map { it.amount })
        assertEquals(100L, result.uncalledReturns["C"] ?: -1L)
    }

    @Test fun showdownSettlementConservesChips() {
        val result = PokerMath.settleShowdown(
            mapOf("A" to 100L, "B" to 200L, "C" to 300L), emptySet(),
            mapOf("A" to cards("As Ah"), "B" to cards("Ks Kh"), "C" to cards("2h 3h")),
            cards("2c 4d 7s 9h Jc"), listOf("C", "B", "A"))
        assertEquals(mapOf("A" to 300L, "B" to 200L, "C" to 100L), result.payouts)
    }

    @Test fun equityOnRiverIsExact() {
        val result = PokerMath.equityVsKnownHands(cards("As Ah"),
            listOf(cards("Kc Kd")), cards("2c 3d 7s 9h Jc"))
        assertTrue(result.exact)
        assertEquals(1, result.runouts)
        assertEquals(1.0, result.potShareEquity, 0.0)
    }

    @Test fun missingEvidenceCannotProduceDiagnostic() {
        val input = VerifiedPokerMathInput("table", "hand", 1L, 12L, false, true,
            HandStage.FLOP, cards("As Ah"), cards("2c 3d 7s"))
        assertNull(VerifiedPokerMathBridge.analyze(input))
        assertNotNull(VerifiedPokerMathBridge.analyze(input.copy(independentTableHandProof = true)))
    }

    @Test fun malformedNumericEvidenceRejects() {
        val input = VerifiedPokerMathInput("table", "hand", 1L, 12L, true, true,
            HandStage.FLOP, cards("As Ah"), cards("2c 3d 7s"),
            potBeforeCall = -200L, callChips = 50L)
        assertNull(VerifiedPokerMathBridge.analyze(input))
    }
}
