package com.example.airemote

import org.junit.Assert.*
import org.junit.Test

/** Future Gradle/JUnit tests; source-only validation here uses RealFrameRegionScenarios.kt. */
class RealFrameRegionJvmTest {
    private fun profile() = RealFrameCalibration("manual", "test.app", 1080, 2340,
        listOf(RealFrameZone(RealFieldRole.SEAT_ACTION, NormalizedTextBox(50, 100, 350, 200), 1),
            RealFrameZone(RealFieldRole.SEAT_ACTION, NormalizedTextBox(650, 100, 950, 200), 2),
            RealFrameZone(RealFieldRole.POT, NormalizedTextBox(400, 300, 600, 400))))
    private fun ticket(n: Long, ms: Long, pkg: String="test.app") = OcrFrameTicket(1,n,ms,pkg,3)

    @Test fun emptyProfileCannotProduceObservation() {
        val reader = RealFrameRegionDiagnostics()
        assertEquals(RegionObservationStatus.NOT_CALIBRATED,
            reader.inspect(ticket(1, 1000),1080,2340,1100,emptyList()).status)
    }

    @Test fun measuredRawPotStillCannotAuthorizeTapOrSaveHistory() {
        val reader = RealFrameRegionDiagnostics()
        assertTrue(reader.configure(profile()))
        val value = reader.inspect(ticket(1,1000),1080,2340,1050,
            listOf(PositionedOcrLine("1200",NormalizedTextBox(430,320,520,350))))
        assertEquals(RegionObservationStatus.OBSERVED,value.status)
        assertEquals(1200L,value.fields.single().numericValue)
        assertFalse(value.authorizesAction)
        assertFalse(value.canCommitOpponentHistory)
    }

    @Test fun actualDollarDecimalsAreReadOnlyExactMinorUnits() {
        val reader = RealFrameRegionDiagnostics()
        assertTrue(reader.configure(profile()))
        val value = reader.inspect(ticket(1,1000),1080,2340,1050,
            listOf(PositionedOcrLine("0.87 $",NormalizedTextBox(430,320,520,350))))
        assertEquals(87L, value.fields.single().amountMinorUnits)
        assertNull(value.fields.single().numericValue)
        assertFalse(value.authorizesAction)
    }

    @Test fun separatelyCalibratedCardBackRegionsAreAcceptedButNotTrustedActions() {
        val calibrated = profile().copy(zones = profile().zones + listOf(
            RealFrameZone(RealFieldRole.SEAT_CARD_BACK,
                NormalizedTextBox(204, 305, 286, 350), 1),
            RealFrameZone(RealFieldRole.SEAT_CARD_BACK,
                NormalizedTextBox(710, 310, 795, 350), 2)
        ))
        assertTrue(calibrated.valid())
        val invalid = calibrated.copy(zones = calibrated.zones + listOf(
            RealFrameZone(RealFieldRole.SEAT_CARD_BACK,
                NormalizedTextBox(204, 305, 286, 350), 9)))
        assertFalse(invalid.valid())
    }

    @Test fun differentSourceOrReplayIsRejected() {
        val reader = RealFrameRegionDiagnostics()
        reader.configure(profile())
        assertEquals(RegionObservationStatus.REJECTED,
            reader.inspect(ticket(1,1000,"other.app"),1080,2340,1100,emptyList()).status)
        assertEquals(RegionObservationStatus.OBSERVED,
            reader.inspect(ticket(2,1200),1080,2340,1250,emptyList()).status)
        assertEquals(RegionObservationStatus.REJECTED,
            reader.inspect(ticket(2,1200),1080,2340,1300,emptyList()).status)
    }
}
