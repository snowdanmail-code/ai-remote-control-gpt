package com.example.airemote

import org.junit.Assert.*
import org.junit.Test

class OneWinCashLobbyBrowsingTest {
    private fun line(value: String, x: Int, y: Int) = PositionedOcrLine(
        value, NormalizedTextBox(x, y, x + 220, y + 25))

    @Test fun recognizesFourRealSortChoicesAndHeader() {
        val snapshot = listOf(
            line("Кэш-Игры", 400, 70),
            line("Отсортировано по", 20, 162),
            line("Ставки по возрастанию", 20, 215),
            line("Ставки по убыванию", 20, 267),
            line("Игроки по возрастанию", 20, 319),
            line("Игроки по убыванию", 20, 371)
        )
        val result = OneWinCashLobbySortParser.read(snapshot)
        assertNotNull(result)
        assertEquals(4, result!!.options.size)
        assertTrue(result.navigable)
        assertEquals(227,
            result.options.getValue(OneWinLobbySort.STAKES_ASC).centerY())
    }

    @Test fun rejectsPartialAmbiguousOrWrongScreenMenu() {
        assertNull(OneWinCashLobbySortParser.read(listOf(
            line("Ставки по возрастанию", 20, 215),
            line("Ставки по убыванию", 20, 267),
            line("Игроки по возрастанию", 20, 319))))
        assertNull(OneWinCashLobbySortParser.read(listOf(
            line("Отсортировано по", 20, 162),
            line("Ставки по возрастанию", 20, 215),
            line("Ставки по возрастанию", 20, 237),
            line("Ставки по убыванию", 20, 267),
            line("Игроки по возрастанию", 20, 319))))
        assertNull(OneWinCashLobbySortParser.read(listOf(
            line("Отсортировано по", 520, 162),
            line("Ставки по возрастанию", 520, 215),
            line("Ставки по убыванию", 520, 267),
            line("Игроки по возрастанию", 520, 319))))
    }

    private fun table(name: String, big: Long = 4L, price: Long = 160L) =
        OneWinCashLobbyTable(name, 4, 6, 2L, big,
            price, price * 2, 60L,
            NormalizedTextBox(720, 215, 940, 251), 230)

    @Test fun repeatedPageStopsUnlimitedSwipesButNewRowsContinue() {
        val tracker = OneWinCashLobbyBrowseTracker(maxSwipes = 4)
        assertTrue(tracker.recordConfirmedPage(listOf(table("A"), table("B"))))
        assertFalse(tracker.recordConfirmedPage(listOf(table("A"), table("B"))))
        assertTrue(tracker.markSwipeSubmitted())
        assertTrue(tracker.recordConfirmedPage(listOf(table("B"), table("C"))))
        assertEquals(3, tracker.distinctTablesSeen())
        assertTrue(tracker.markSwipeSubmitted())
        assertTrue(tracker.recordConfirmedPage(listOf(table("C"), table("B"))))
        assertEquals(1, tracker.repeatedPages)
        assertTrue(tracker.recordConfirmedPage(listOf(table("B"), table("C"))))
        assertEquals(2, tracker.repeatedPages)
        assertFalse(tracker.canSwipe())
        tracker.reset()
        assertTrue(tracker.canSwipe())
        assertEquals(0, tracker.distinctTablesSeen())
    }

    @Test fun pageSignatureIgnoresUnreliableAveragePotAndPosition() {
        val tracker = OneWinCashLobbyBrowseTracker()
        val a = table("A")
        val b = a.copy(averagePotCents = 999L, rowCenterY = 700,
            buyInOcrBox = NormalizedTextBox(720, 700, 940, 731))
        assertEquals(tracker.pageSignature(listOf(a)), tracker.pageSignature(listOf(b)))
    }
    @Test fun partiallyRecognizedRussianMenuStillBlocksTableEntry() {
        // One option is not recognized by the offline OCR on the user's
        // compressed 354x768 example. Three confirmed labels + the header
        // suffice to identify an obscuring dropdown but never a table row.
        val menu = listOf(
            line("Отсортировано по", 20, 162),
            line("Ставки по возрастанию", 20, 215),
            line("Ставки по убыванию", 20, 267),
            line("Игроки по возрастанию", 20, 319),
            line("Кэш-Игры", 380, 70),
            line("Холдем", 30, 140),
            line("5/6", 540, 275),
            line("Бай-ин: 8$/20$", 740, 231)
        )
        assertEquals(3, OneWinCashLobbySortParser.read(menu)?.options?.size)
        val scan = OneWinCashLobbyParser.read(menu)
        assertFalse(scan.headerVerified)
        assertEquals("SORT_MENU_VISIBLE", scan.reason)
        assertTrue(scan.tables.isEmpty())
    }

}
