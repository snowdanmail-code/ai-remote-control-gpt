package com.example.airemote

import org.junit.Assert.*
import org.junit.Test

class OneWinRecordedCashTableUiTest {
    private fun line(text: String, left: Int, top: Int,
                     width: Int = 140, height: Int = 27) =
        PositionedOcrLine(text, NormalizedTextBox(left, top,
            left + width, top + height))

    @Test fun recognizesTheThreeDistinctRussianTurnButtons() {
        val first = OneWinRecordedCashTableUi.read(listOf(
            line("Фолд", 55, 945),
            line("Чек", 386, 945),
            line("Ставка", 740, 945)
        ))
        assertEquals(OneWinRecordedTablePanel.HERO_TURN, first.panel)
        assertEquals(listOf(OneWinRecordedAction.FOLD,
            OneWinRecordedAction.CHECK, OneWinRecordedAction.BET),
            first.actions.map { it.action })
        assertFalse(first.authorizesGesture)

        val callAndRaise = OneWinRecordedCashTableUi.read(listOf(
            line("Фолд", 55, 945),
            line("Колл 0.02 $", 386, 945),
            line("Рейз", 740, 945)
        ))
        assertEquals(OneWinRecordedTablePanel.HERO_TURN, callAndRaise.panel)
        assertEquals(2L, callAndRaise.actions[1].amountCents)
    }

    @Test fun betSizeEditorIsNotTheFinalBetOrAnAutomaticTap() {
        val observed = OneWinRecordedCashTableUi.read(listOf(
            line("Мин", 20, 945),
            line("1/2", 188, 945),
            line("Банк", 365, 945),
            line("Макс", 539, 945),
            line("Подтвердить", 715, 939, 235),
            line("0.08 $", 742, 977, 140)
        ))
        assertEquals(OneWinRecordedTablePanel.BET_SIZE_EDITOR, observed.panel)
        assertEquals(8L, observed.proposedStakeCents)
        assertTrue(observed.actions.isEmpty())
        assertFalse(observed.authorizesGesture)
    }

    @Test fun doesNotGuessAnAmountOrApprovePartialBetEditor() {
        val withoutAmount = OneWinRecordedCashTableUi.read(listOf(
            line("Мин", 20, 945),
            line("1/2", 188, 945),
            line("Банк", 365, 945),
            line("Макс", 539, 945),
            line("Подтвердить", 715, 939, 235)
        ))
        assertEquals(OneWinRecordedTablePanel.UNKNOWN, withoutAmount.panel)
        val misleading = OneWinRecordedCashTableUi.read(listOf(
            line("Фолд", 55, 945),
            line("Чек", 386, 945),
            line("Подтвердить", 715, 939, 235)
        ))
        assertEquals(OneWinRecordedTablePanel.UNKNOWN, misleading.panel)
    }

    @Test fun preactionAndShowdownControlsAreNotPlayerTurn() {
        val pre = OneWinRecordedCashTableUi.read(listOf(
            line("Чек/Фолд", 60, 950, 230),
            line("Чек", 390, 950),
            line("Чек/Уравнять все ставки", 718, 945, 245)
        ))
        assertEquals(OneWinRecordedTablePanel.PREACTION, pre.panel)
        val showdown = OneWinRecordedCashTableUi.read(listOf(
            line("Скрыть карты при выигрыше", 200, 938, 240),
            line("Открыть карты", 555, 938, 190),
            line("Банк: 0.45 $", 420, 360)
        ))
        assertEquals(OneWinRecordedTablePanel.SHOWDOWN_CONTROLS, showdown.panel)
    }

    @Test fun twoFramesConfirmOnlyTheReadOnlyPresentation() {
        val tracker = OneWinRecordedTableUiConsistency()
        val one = OneWinRecordedCashTableUi.read(listOf(
            line("Фолд", 55, 945), line("Чек", 386, 945),
            line("Ставка", 740, 945)
        ))
        val t1 = OcrFrameTicket(7L, 15L, 1_000L, "com.example.game", 3L)
        val t2 = t1.copy(frameSequence = 16L, capturedAtMs = 2_000L)
        assertEquals(OneWinRecordedTablePanel.UNKNOWN, tracker.observe(t1, one).panel)
        val stable = tracker.observe(t2, one)
        assertEquals(OneWinRecordedTablePanel.HERO_TURN, stable.panel)
        assertFalse(stable.authorizesGesture)
        val moved = t2.copy(windowGeneration = 4L, frameSequence = 17L,
            capturedAtMs = 3_000L)
        assertEquals(OneWinRecordedTablePanel.UNKNOWN,
            tracker.observe(moved, one).panel)
    }

    @Test fun heroIsBottomVisibleSeatNotASeatNumber() {
        val coords = listOf(
            500 to 200, 110 to 300, 830 to 300,
            400 to 780, 110 to 640, 830 to 640
        )
        val zones = coords.mapIndexed { index, (x,y) ->
            RealFrameZone(RealFieldRole.SEAT_ACTION,
                NormalizedTextBox(x, y-50, x+110, y-20), index)
        } + coords.mapIndexed { index, (x,y) ->
            RealFrameZone(RealFieldRole.SEAT_STACK,
                NormalizedTextBox(x, y-5, x+110, y+20), index)
        }
        val profile = RealFrameCalibration("video-1080x2340",
            "com.example.game",1080,2340,zones)
        assertTrue(profile.valid())
        assertEquals(3, OneWinPortraitHeroSeat.inferBottomSeat(profile))
        // Numbers may rotate: a different index can be the same bottom slot.
        val rotated = profile.copy(zones = profile.zones.map { zone ->
            zone.copy(seatIndex = (requireNotNull(zone.seatIndex) + 2) % 6)
        })
        assertEquals(5, OneWinPortraitHeroSeat.inferBottomSeat(rotated))
    }
}
