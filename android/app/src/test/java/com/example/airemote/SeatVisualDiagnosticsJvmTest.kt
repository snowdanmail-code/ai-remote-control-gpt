package com.example.airemote

import org.junit.Assert.*
import org.junit.Test

/** Source-level Android JUnit tests; executed only when Gradle is available. */
class SeatVisualDiagnosticsJvmTest {
    private fun calibration() = SeatLayoutCalibration("manual-measurement-1", "test.app", 1080, 2340,
        listOf(SeatRegion(1, NormalizedTextBox(50, 250, 250, 450)),
               SeatRegion(2, NormalizedTextBox(750, 250, 950, 450))))
    private fun cue(text: String) = PositionedOcrLine(text, NormalizedTextBox(100, 300, 180, 340))
    private fun ticket(seq: Long, at: Long, win: Long = 1) = OcrFrameTicket(5, seq, at, "test.app", win)

    @Test fun notCalibratedIsNeverStable() {
        assertEquals(SeatDiagnosticStatus.NOT_CALIBRATED,
            SeatVisualDiagnostics().inspect(ticket(1, 1000), listOf(cue("CALL")), 1080, 2340, 1050).status)
    }

    @Test fun twoIndependentFramesAreOnlyDiagnostic() {
        val m = SeatVisualDiagnostics()
        assertTrue(m.configure(calibration()))
        assertTrue(m.inspect(ticket(1, 1000), listOf(cue("CALL")), 1080, 2340, 1050).stableCues.isEmpty())
        val report = m.inspect(ticket(2, 1200), listOf(cue("CALL")), 1080, 2340, 1250)
        assertEquals(SeatDiagnosticStatus.STABLE_DIAGNOSTIC, report.status)
        assertEquals(SeatCue(1, SeatCueAction.CALL), report.stableCues.single())
    }

    @Test fun changingWindowErasesEvidenceAndCalibration() {
        val m = SeatVisualDiagnostics()
        m.configure(calibration())
        m.inspect(ticket(1, 1000), listOf(cue("CALL")), 1080, 2340, 1050)
        assertEquals(SeatDiagnosticStatus.INVALIDATED,
            m.inspect(ticket(2, 1200, win = 2), listOf(cue("CALL")), 1080, 2340, 1250).status)
        assertEquals(SeatDiagnosticStatus.NOT_CALIBRATED,
            m.inspect(ticket(3, 1400, win = 2), listOf(cue("CALL")), 1080, 2340, 1450).status)
    }

    @Test fun conflictingCuesNeverBecomeStable() {
        val m = SeatVisualDiagnostics()
        m.configure(calibration())
        m.inspect(ticket(1, 1000), listOf(cue("CALL")), 1080, 2340, 1050)
        assertEquals(SeatDiagnosticStatus.REJECTED,
            m.inspect(ticket(2, 1200), listOf(cue("CALL"), cue("RAISE")), 1080, 2340, 1250).status)
        assertTrue(m.inspect(ticket(3, 1400), listOf(cue("CALL")), 1080, 2340, 1450).stableCues.isEmpty())
    }
}
