package com.example.airemote

import org.junit.Assert.*
import org.junit.Test

class OneWinMinimumBuyInPolicyTest {
    private fun box(x: Int, y: Int, w: Int = 100, h: Int = 25) =
        NormalizedTextBox(x, y, x + w, y + h)
    private fun reading(
        value: Long = 160L, debit: Long = value, minimum: Long = 160L,
        available: Long = 6_036L,
        minControl: NormalizedTextBox? = box(80,300,190,38),
        confirm: NormalizedTextBox? = box(450,945,85,25)
    ) = OneWinBuyInDialogReading(
        status = OneWinBuyInReadingStatus.VERIFIED,
        smallBlindCents = 2L, bigBlindCents = 4L,
        availableCents = available, debitCents = debit,
        minimumCents = minimum, maximumCents = 400L,
        inputCents = value, okBox = confirm,
        autoRebuyControlVisible = true,
        minButtonBox = minControl
    )
    private val limits = OneWinCashTableLimits(4L,160L)

    @Test fun screenExampleAutomaticallyConfirmsExactlyOneSixtyMinimum() {
        val d = OneWinMinimumBuyInPolicy.evaluate(reading(), limits, false)
        assertEquals(OneWinMinBuyInStep.PRESS_OK, d.step)
        assertEquals(160L, d.amountCents)
        assertEquals(box(450,945,85,25), d.target)
        assertTrue(d.explanation.contains("Минимум"))
    }

    @Test fun differentBlindsOrMinimumAbortTheSelectedTableBuyIn() {
        val table = OneWinCashLobbyTable("Viana MJP #5", 5, 6, 2L, 4L,
            160L, 400L, null, box(740,230), 230)
        assertEquals(OneWinMinBuyInStep.PRESS_OK,
            OneWinMinimumBuyInPolicy.evaluate(reading(), limits, false, table).step)
        assertEquals(OneWinMinBuyInStep.RECHECK_OCR,
            OneWinMinimumBuyInPolicy.evaluate(reading(), limits, false,
                table.copy(minBuyInCents = 80L)).step)
        assertEquals(OneWinMinBuyInStep.RECHECK_OCR,
            OneWinMinimumBuyInPolicy.evaluate(reading(value = 250L), limits, false,
                table.copy(smallBlindCents = 1L)).step)
    }

    @Test fun replayFromTableChoiceThroughSeatMinimumAndConfirmedArrival() {
        val table = OneWinCashLobbyTable("Viana MJP #5", 5, 6, 2L, 4L,
            160L, 400L, null, box(740,230), 230)
        val full = table.copy(label = "Full", occupiedSeats = 6)
        val expected = OneWinCashLobbySelector.choose(listOf(full, table), limits)!!.table
        assertEquals(table, expected)

        val first = OcrFrameTicket(7L, 1L, 1000L, "com.example.game", 3L)
        val vacancy = OneWinVacantSeatMarker(NormalizedTextBox(100,355,168,405), 0.96)
        val seats = OneWinFreeSeatAgreement()
        assertTrue(seats.observe(first, listOf(vacancy)).isEmpty())
        val second = first.copy(frameSequence = 2L, capturedAtMs = 1600L)
        assertEquals(OneWinPreSeatSlot.LEFT_UPPER,
            OneWinVacantSeatGeometry.choose(seats.observe(second, listOf(vacancy)))?.slot)

        val dialog = OneWinBuyInAgreement()
        val aboveMinimum = reading(value = 240L)
        val third = second.copy(frameSequence = 3L, capturedAtMs = 2600L)
        val fourth = third.copy(frameSequence = 4L, capturedAtMs = 3800L)
        assertNull(dialog.observe(third, aboveMinimum))
        val agreed = dialog.observe(fourth, aboveMinimum)
        assertEquals(OneWinMinBuyInStep.PRESS_MIN,
            OneWinMinimumBuyInPolicy.evaluate(agreed, limits, false, expected).step)
        dialog.reset()
        val fifth = fourth.copy(frameSequence = 5L, capturedAtMs = 5200L)
        val sixth = fifth.copy(frameSequence = 6L, capturedAtMs = 6600L)
        assertNull(dialog.observe(fifth, reading()))
        val minimum = OneWinMinimumBuyInPolicy.evaluate(
            dialog.observe(sixth, reading()), limits, true, expected)
        assertEquals(OneWinMinBuyInStep.PRESS_OK, minimum.step)
        assertEquals(160L, minimum.amountCents)

        val seated = OneWinBottomHeroAfterBuyInAgreement()
        val hero = OneWinHeroAfterBuyInFrame("com.example.game", 7L, 3L, 7L,
            8000L, 7L, 7900L, listOf("AH", "KD"))
        assertFalse(seated.observe(hero, 8100L, true, false, 6700L))
        assertTrue(seated.observe(hero.copy(frameSequence = 8L, capturedAtMs = 9500L,
            cardCapturedAtMs = 9400L), 9600L, true, false, 6700L))
    }

    @Test fun higherDefaultChoosesMinThenRechecksBeforeOk() {
        val high = reading(value = 250L)
        val initial = OneWinMinimumBuyInPolicy.evaluate(high, limits, false)
        assertEquals(OneWinMinBuyInStep.PRESS_MIN, initial.step)
        assertEquals(high.minButtonBox, initial.target)
        assertEquals(160L, initial.amountCents)

        val premature = OneWinMinimumBuyInPolicy.evaluate(high, limits, true)
        assertEquals(OneWinMinBuyInStep.RECHECK_OCR, premature.step)
        val postMin = OneWinMinimumBuyInPolicy.evaluate(reading(), limits, true)
        assertEquals(OneWinMinBuyInStep.PRESS_OK, postMin.step)
        assertEquals(160L, postMin.amountCents)
    }

    @Test fun underfundedOrAboveCapCannotTriggerAnyBuyIn() {
        assertEquals(OneWinMinBuyInStep.RECHECK_OCR,
            OneWinMinimumBuyInPolicy.evaluate(reading(available = 100L), limits, false).step)
        assertEquals(OneWinMinBuyInStep.RECHECK_OCR,
            OneWinMinimumBuyInPolicy.evaluate(reading(),
                OneWinCashTableLimits(3L,160L), false).step)
        assertEquals(OneWinMinBuyInStep.RECHECK_OCR,
            OneWinMinimumBuyInPolicy.evaluate(reading(),
                OneWinCashTableLimits(4L,100L), false).step)
        assertEquals(OneWinMinBuyInStep.RECHECK_OCR,
            OneWinMinimumBuyInPolicy.evaluate(null, limits, false).step)
    }

    @Test fun unknownButtonsOrMismatchCannotApproveOk() {
        assertEquals(OneWinMinBuyInStep.RECHECK_OCR,
            OneWinMinimumBuyInPolicy.evaluate(reading(confirm = null),limits,false).step)
        assertEquals(OneWinMinBuyInStep.RECHECK_OCR,
            OneWinMinimumBuyInPolicy.evaluate(reading(value=160L, debit=200L),
                limits,false).step)
        assertEquals(OneWinMinBuyInStep.RECHECK_OCR,
            OneWinMinimumBuyInPolicy.evaluate(reading(value=240L,minControl=null),
                limits,false).step)
    }

    @Test fun minimumNotFixedToExampleAndNeverChoosesMax() {
        val other = reading(value=120L, debit=120L, minimum=120L)
        val decision = OneWinMinimumBuyInPolicy.evaluate(other,limits,false)
        assertEquals(OneWinMinBuyInStep.PRESS_OK, decision.step)
        assertEquals(120L, decision.amountCents)
        val needMin = OneWinMinimumBuyInPolicy.evaluate(
            other.copy(inputCents=200L,debitCents=200L),limits,false)
        assertEquals(OneWinMinBuyInStep.PRESS_MIN, needMin.step)
    }

    @Test fun parserProvidesActualMinimumButtonGeometry() {
        val lines = listOf(
            PositionedOcrLine("Бай-Ин", box(30,60)),
            PositionedOcrLine("Тип игры: Холдем NL 0.02 $/0.04 $",
                box(40,129,880,26)),
            PositionedOcrLine("Доступно средств: 60.36 $",
                box(40,180,880,26)),
            PositionedOcrLine("С вашего счета будет списано: 1.60 $",
                box(40,235,880,26)),
            PositionedOcrLine("Мин 1.60 $",box(65,305,210,26)),
            PositionedOcrLine("1.60 $",box(435,305,145,26)),
            PositionedOcrLine("Макс 4 $",box(750,305,195,26)),
            PositionedOcrLine("Авто бай-ин и авто ребай",box(40,440,800,26)),
            PositionedOcrLine("Ок",box(470,949,55,26))
        )
        val parsed = OneWinBuyInDialogParser.read(lines)
        assertEquals(OneWinBuyInReadingStatus.VERIFIED,parsed.status)
        assertEquals(OneWinMinBuyInStep.PRESS_OK,
            OneWinMinimumBuyInPolicy.evaluate(parsed,limits,false).step)
        assertEquals(box(65,305,210,26),parsed.minButtonBox)
        assertEquals(160L,parsed.minimumCents)
    }
}
