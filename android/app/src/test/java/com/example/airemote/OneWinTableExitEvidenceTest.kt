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

    @Test fun unreadableOcrKeepsRecheckingWithBackoffRatherThanStopping() {
        val retry = OneWinExitRecognitionRetryPolicy()
        assertTrue(retry.mayRead(0L))
        retry.observe(1_000L, recognized = false)
        assertEquals(1, retry.inconclusiveFrames)
        assertFalse(retry.mayRead(2_000L))
        assertTrue(retry.mayRead(2_300L))
        retry.observe(2_300L, recognized = false)
        assertFalse(retry.mayRead(4_800L))
        assertTrue(retry.mayRead(4_900L))
        retry.observe(4_900L, recognized = false)
        assertTrue(retry.mayRead(10_100L))
        retry.observe(10_100L, recognized = false)
        assertFalse(retry.mayRead(18_000L))
        assertTrue(retry.mayRead(18_100L))
        // A readable frame resets the failure counter and shortens the cadence.
        retry.observe(18_100L, recognized = true)
        assertEquals(0, retry.inconclusiveFrames)
        assertFalse(retry.mayRead(19_399L))
        assertTrue(retry.mayRead(19_400L))
        retry.reset()
        assertTrue(retry.mayRead(1L))
    }

    @Test fun repeatedUncertaintyIsNeverMisinterpretedAsConsentToExit() {
        val gate = OneWinTableExitEvidenceGate()
        val retry = OneWinExitRecognitionRetryPolicy()
        repeat(16) {
            retry.observe(1_000L + it * 9_000L, recognized = false)
        }
        assertEquals(16, retry.inconclusiveFrames)
        assertFalse(gate.inspect(null, stage(), 145_000L, false, false))
        assertFalse(gate.inspect(sample(1, 145_000L),
            stage(HandStage.PREFLOP), 145_100L, false, false))
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

    @Test fun recognizesActualCloseTableConfirmationAndRightSideYes() {
        val modal = listOf(
            line("Вы уверены, что хотите закрыть стол?", 158, 481, 676),
            line("Нет", 302, 555, 60),
            line("Да", 675, 554, 55)
        )
        val dialog = OneWinTableExitMenuParser.confirmationDialog(modal)
        assertNotNull(dialog)
        assertEquals(702, dialog!!.yesBox.centerX())
        assertEquals(332, dialog.noBox.centerX())
    }

    @Test fun wrappedQuestionIsAcceptedButStrayYesAndWrongDialogAreRejected() {
        val wrapped = listOf(
            line("Вы уверены, что хотите", 130, 468, 580),
            line("закрыть стол?", 290, 505, 350),
            line("Нет", 300, 559, 45),
            line("Да", 698, 558, 35)
        )
        assertNotNull(OneWinTableExitMenuParser.confirmationDialog(wrapped))
        assertNull(OneWinTableExitMenuParser.confirmationDialog(
            wrapped.filterNot { it.text == "Нет" }))
        assertNull(OneWinTableExitMenuParser.confirmationDialog(
            wrapped.map { if (it.text == "закрыть стол?") it.copy(text = "удалить счёт?")
                else it }))
        assertNull(OneWinTableExitMenuParser.confirmationDialog(
            wrapped.map { if (it.text == "Да") it.copy(box =
                NormalizedTextBox(280, 558, 315, 587)) else it }))
        assertNull(OneWinTableExitMenuParser.confirmationDialog(
            wrapped + line("Да", 720, 580, 35)))
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
