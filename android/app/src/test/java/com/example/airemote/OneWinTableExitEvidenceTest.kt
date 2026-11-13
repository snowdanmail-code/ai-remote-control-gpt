package com.example.airemote

import org.junit.Assert.*
import org.junit.Test

class OneWinTableExitEvidenceTest {
    private fun sample(sequence: Long, time: Long,
                       count: Int = 3, hero: Int = 0,
                       epoch: Long = 5L): OneWinExitSeatObservation =
        OneWinExitSeatObservation(
            "com.example.onetable", epoch, 2L, sequence, time, hero,
            (0..5).associateWith { index ->
                if (index < count) OneWinSeatPresence.OCCUPIED
                else OneWinSeatPresence.EMPTY
            }, 6
        )
    private fun stage(phase: HandStage = HandStage.WAITING, trusted: Boolean = true) =
        HandStateSnapshot(phase, trusted, 8L, 42L, "verified test fixture")

    @Test fun exitsOnlyAfterThreeSeparateStableFramesAtThreeSeats() {
        val gate = OneWinTableExitEvidenceGate()
        assertFalse(gate.inspect(sample(1, 1000), stage(), 1200, false, false))
        assertFalse(gate.inspect(sample(2, 2500), stage(), 2700, false, false))
        assertTrue(gate.inspect(sample(3, 4000), stage(), 4200, false, false))
    }

    @Test fun neverInfersEmptySeatsOrAssumesHeroAtTable() {
        val gate = OneWinTableExitEvidenceGate()
        val incomplete = sample(1, 1000).copy(
            seats = mapOf(0 to OneWinSeatPresence.OCCUPIED,
                1 to OneWinSeatPresence.OCCUPIED, 2 to OneWinSeatPresence.OCCUPIED))
        // A snapshot with only visible occupied positions is NOT complete
        // if the reporter knows 6 positions. The source must send all seats.
        assertFalse(gate.inspect(incomplete, stage(), 1100, false, false))
        assertFalse(gate.inspect(sample(1, 1000, hero = 5), stage(), 1100, false, false))
        assertFalse(gate.inspect(sample(1, 1000).copy(seats = emptyMap()),
            stage(), 1100, false, false))
    }

    @Test fun activeHandUntrustedStageStopAndPendingBetAlwaysReset() {
        val gate = OneWinTableExitEvidenceGate()
        assertFalse(gate.inspect(sample(1, 1000), stage(HandStage.PREFLOP), 1100, false, false))
        assertFalse(gate.inspect(sample(2, 2500), stage(trusted = false), 2600, false, false))
        assertFalse(gate.inspect(sample(3, 4000), stage(), 4100, true, false))
        assertFalse(gate.inspect(sample(4, 5500), stage(), 5600, false, true))
        assertFalse(gate.inspect(sample(5, 7000), stage(), 7100, false, false))
    }

    @Test fun extraPlayerAndChangedWindowPreventExit() {
        val gate = OneWinTableExitEvidenceGate()
        assertFalse(gate.inspect(sample(1, 1000), stage(), 1100, false, false))
        assertFalse(gate.inspect(sample(2, 2500, count = 4), stage(), 2600, false, false))
        assertFalse(gate.inspect(sample(3, 4000), stage(), 4100, false, false))
        assertFalse(gate.inspect(sample(4, 5500, epoch = 9), stage(), 5600, false, false))
        assertFalse(gate.inspect(sample(5, 7000), stage(), 7100, false, false))
    }

    private fun line(text: String, x: Int, y: Int, w: Int = 225) =
        PositionedOcrLine(text, NormalizedTextBox(x, y, x + w, y + 32))

    @Test fun detectsRealRussianLeaveMenuOnlyWithSupportingLabels() {
        val menu = listOf(
            line("Покинуть игру", 38, 126),
            line("Касса", 50, 322),
            line("Настройки", 55, 456),
            line("Назад в лобби", 47, 520)
        )
        assertNotNull(OneWinTableExitMenuParser.leaveButton(menu))
        assertNull(OneWinTableExitMenuParser.leaveButton(listOf(menu.first())))
        assertNull(OneWinTableExitMenuParser.leaveButton(
            listOf(line("Покинуть игру", 700, 120), menu[1])))
        assertNull(OneWinTableExitMenuParser.leaveButton(
            menu.map { if (it.text == "Покинуть игру") it.copy(text = "Покинуть ставку")
                else it }))
    }

    @Test fun verifiesLobbiesWithoutMistakingDrawerForLobby() {
        val lobby = listOf(
            line("Кэш-Игры", 410, 53),
            line("76 Столы", 440, 126)
        )
        assertTrue(OneWinTableExitMenuParser.lobbyVisible(lobby))
        assertFalse(OneWinTableExitMenuParser.lobbyVisible(
            listOf(line("Кэш-Игры", 410, 53))))
        assertFalse(OneWinTableExitMenuParser.lobbyVisible(
            listOf(line("Лобби", 410, 53), line("76 Столы", 440, 126))))
    }
}
