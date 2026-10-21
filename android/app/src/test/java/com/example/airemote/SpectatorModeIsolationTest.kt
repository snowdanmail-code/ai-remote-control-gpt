package com.example.airemote

import org.junit.Test
import org.junit.After
import org.junit.Assert.*

class SpectatorModeIsolationTest {
    @After fun restoreDefault() {
        CaptureObservationMode.select(CaptureRole.PLAYER, false, true)
    }

    @Test fun roleCannotChangeWhileCaptureActiveOrStopReleased() {
        assertFalse(CaptureObservationMode.select(CaptureRole.SPECTATOR, true, true))
        assertFalse(CaptureObservationMode.select(CaptureRole.SPECTATOR, false, false))
        assertTrue(CaptureObservationMode.select(CaptureRole.SPECTATOR, false, true))
        assertTrue(CaptureObservationMode.isSpectator())
        assertFalse(CaptureObservationMode.select(CaptureRole.PLAYER, true, true))
    }

    @Test fun spectatorConsensusNeedsTwoFreshDifferentFrames() {
        val consensus = SpectatorBoardConsensus()
        val community = VisibleCommunityReading(listOf("Ah", "Td", "3c"), true, "visible")
        assertNull(consensus.accept(community, 2, 1, 1000, 1200, "org.example.table"))
        val accepted = consensus.accept(community, 2, 2, 2000, 2150, "org.example.table")
        assertNotNull(accepted)
        assertEquals(listOf("Ah", "Td", "3c"), accepted!!.cards)
        assertFalse(accepted.authorizesAction)
    }

    @Test fun spectatorNeverGuessesAbsentBoardOrDuplicateCards() {
        val consensus = SpectatorBoardConsensus()
        val beforeFlop = VisibleCommunityReading(emptyList(), false, "before flop")
        assertNull(consensus.accept(beforeFlop, 1, 1, 1000, 1100, "org.example.table"))
        val duplicate = VisibleCommunityReading(listOf("Ah", "Td", "Ah"), true, "incorrect")
        assertNull(consensus.accept(duplicate, 1, 2, 1400, 1500, "org.example.table"))
        val valid = VisibleCommunityReading(listOf("Ah", "Td", "3c"), true, "visible")
        assertNull(consensus.accept(valid, 1, 3, 1600, 1700, "org.example.table"))
        assertNull(consensus.accept(valid, 1, 4, 1900, 2050, "other.package"))
        assertNull(consensus.accept(valid, 1, 5, 2600, 5000, "other.package"))
    }
}
