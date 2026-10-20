package com.example.airemote

import org.junit.Assert.*
import org.junit.Test

/** Android Gradle/JUnit tests: NOT run by tools/run_jvm_tests.sh. */
class SeatActionTransitionJvmTest {
    private fun snap(seq: Long, at: Long, bet: Long, stack: Long, turn: Int?,
                     hand: String = "hand-1") = SeatStateSnapshot(
        OcrFrameTicket(4, seq, at, "test.app", 9), "table-1", hand,
        true, true, "FLOP", turn, mapOf(1 to bet, 2 to 40L),
        mapOf(1 to stack, 2 to 160L), setOf(1, 2), 40, 90)
    private fun label() = SeatVisualReport(SeatDiagnosticStatus.STABLE_DIAGNOSTIC,
        listOf(SeatCue(1, SeatCueAction.CALL)), listOf(SeatCue(1, SeatCueAction.CALL)), 3)

    @Test fun plausibleDeltaNeverAuthorizesActionOrHistory() {
        val report = SeatActionTransitionDiagnostics.evaluate(
            snap(1, 1000, 20, 180, 1), snap(2, 1200, 40, 160, 2),
            snap(3, 1400, 40, 160, 2), label(), 1450)
        assertEquals(SeatTransitionStatus.PLAUSIBLE_DIAGNOSTIC, report.status)
        assertFalse(report.canAuthorizeGameplay)
        assertFalse(report.canEnterOpponentHistory)
    }

    @Test fun mismatchedHandInvalidates() {
        val report = SeatActionTransitionDiagnostics.evaluate(
            snap(1, 1000, 20, 180, 1), snap(2, 1200, 40, 160, 2, "other-hand"),
            snap(3, 1400, 40, 160, 2, "other-hand"), label(), 1450)
        assertEquals(SeatTransitionStatus.INVALIDATED, report.status)
    }

    @Test fun stopInvalidatesEvenValidData() {
        val report = SeatActionTransitionDiagnostics.evaluate(
            snap(1, 1000, 20, 180, 1), snap(2, 1200, 40, 160, 2),
            snap(3, 1400, 40, 160, 2), label(), 1450, operatorStopped = true)
        assertEquals(SeatTransitionStatus.INVALIDATED, report.status)
    }

    @Test fun labelWithoutNumericDeltaIsInconclusive() {
        val report = SeatActionTransitionDiagnostics.evaluate(
            snap(1, 1000, 20, 180, 1), snap(2, 1200, 20, 180, 2),
            snap(3, 1400, 20, 180, 2), label(), 1450)
        assertEquals(SeatTransitionStatus.INCONCLUSIVE, report.status)
    }
}
