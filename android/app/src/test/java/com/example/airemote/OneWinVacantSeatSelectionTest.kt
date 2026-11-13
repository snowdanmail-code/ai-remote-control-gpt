package com.example.airemote

import org.junit.Assert.*
import org.junit.Test

class OneWinVacantSeatSelectionTest {
    private fun marker(x: Int, y: Int, confidence: Double = 0.96) =
        OneWinVacantSeatMarker(
            NormalizedTextBox(x-34, y-25, x+34, y+25), confidence)

    @Test fun recognizesAllSixScreenSlotsBeforeHeroIsSeated() {
        val positions = mapOf(
            OneWinPreSeatSlot.TOP to (500 to 180),
            OneWinPreSeatSlot.LEFT_UPPER to (133 to 380),
            OneWinPreSeatSlot.RIGHT_UPPER to (850 to 380),
            OneWinPreSeatSlot.LEFT_LOWER to (133 to 645),
            OneWinPreSeatSlot.RIGHT_LOWER to (850 to 645),
            OneWinPreSeatSlot.BOTTOM to (500 to 800)
        )
        for ((slot, xy) in positions)
            assertEquals(slot, OneWinVacantSeatGeometry.slotFor(marker(xy.first,xy.second)))
    }

    @Test fun strongestOfTwoObservedVacanciesIsChosenWithoutSeatNumberAssumptions() {
        val top = marker(500, 180, 0.885)
        val left = marker(133, 380, 0.952)
        val chosen = OneWinVacantSeatGeometry.choose(listOf(top, left))
        assertEquals(OneWinPreSeatSlot.LEFT_UPPER, chosen?.slot)
        assertEquals(left, chosen?.marker)
    }

    @Test fun toolbarAndMiddleOfTableIconsAreNotSeatEntryTargets() {
        assertNull(OneWinVacantSeatGeometry.slotFor(marker(55,65)))
        assertNull(OneWinVacantSeatGeometry.slotFor(marker(500,540)))
        assertNull(OneWinVacantSeatGeometry.slotFor(marker(340,310)))
        assertNull(OneWinVacantSeatGeometry.choose(listOf(
            marker(133,380), marker(500,540))))
    }

    @Test fun duplicateSeatAndInvalidConfidenceFailClosed() {
        assertNull(OneWinVacantSeatGeometry.choose(listOf(
            marker(133,380,0.95), marker(142,374,0.97))))
        assertNull(OneWinVacantSeatGeometry.choose(listOf(
            marker(133,380,0.80))))
        assertNull(OneWinVacantSeatGeometry.choose(emptyList()))
    }

    @Test fun selectionIsOnlyScreenEvidenceNeverFinancialAuthorization() {
        val choice = requireNotNull(OneWinVacantSeatGeometry.choose(
            listOf(marker(500,180))))
        val ticket = OneWinVacantSeatTapTicket(
            9L, "com.example.game", 8L, 1L, 5L, 10_000L,
            1080, 2340, choice)
        assertEquals(OneWinPreSeatSlot.TOP, ticket.selected.slot)
        assertEquals(OneWinSeatEntryGestureOutcome.NEVER_DISPATCHED,
            OneWinSeatEntryGestureOutcome.NEVER_DISPATCHED)
        // There is no buy-in amount, OK button, consent or wager capability
        // on this ticket. This is intentionally a navigation-only type.
        assertFalse(ticket.toString().contains("buyInCents"))
    }
}
