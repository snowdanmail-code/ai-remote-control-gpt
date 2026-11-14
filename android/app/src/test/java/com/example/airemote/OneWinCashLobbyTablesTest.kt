package com.example.airemote

import org.junit.Assert.*
import org.junit.Test

class OneWinCashLobbyTablesTest {
    private fun line(text: String, x: Int, y: Int,
                     width: Int = 130, height: Int = 18) =
        PositionedOcrLine(text, NormalizedTextBox(x, y, x + width, y + height))

    /** Based on the owner-supplied 354x768 portrait lobby screenshot. */
    private fun screenshotOcr(): List<PositionedOcrLine> = listOf(
        line("Кэш-Игры", 410, 55, 190),
        line("76 Столы", 420, 118, 160),
        line("Холдем NL", 45, 205, 190),
        line("Бай-ин:", 742, 221, 160),
        line("8$/20 $", 742, 241, 170),
        line("Palma BP #4", 55, 253, 320),
        line("Средний банк: 4,93 $", 55, 280, 345),
        line("5/6", 515, 280, 110),
        line("0.10$/0.20$", 737, 280, 215),
        line("Холдем NL", 45, 305, 190),
        line("Бай-ин:", 742, 322, 160),
        line("1.60$/4 $", 742, 342, 170),
        line("Viana MJP #5", 55, 353, 320),
        line("Средний банк: 0.61 $", 55, 380, 345),
        line("5/6", 515, 380, 110),
        line("0.02$/0.04$", 737, 380, 215),
        line("Холдем NL", 45, 405, 190),
        line("Бай-ин:", 742, 421, 160),
        line("12$/30 $", 742, 441, 170),
        line("Norman #2", 55, 452, 320),
        line("Средний банк: 6.35 $", 55, 479, 345),
        line("5/6", 515, 479, 110),
        line("0.15$/0.30$", 737, 479, 215),
        line("Холдем NL", 45, 505, 190),
        line("Бай-ин:", 742, 521, 160),
        line("0.80$/2 $", 742, 541, 170),
        line("Lubbock BP #3", 55, 553, 320),
        line("Средний банк: 1.39 $", 55, 579, 345),
        line("5/6", 515, 579, 110),
        line("0.01$/0.02$", 737, 579, 215)
    )

    @Test fun suppliedLobbyHasFourIndependentlyParsedRows() {
        val scan = OneWinCashLobbyParser.read(screenshotOcr())
        assertTrue(scan.reason, scan.headerVerified)
        assertEquals(scan.reason, 4, scan.tables.size)
        val a = scan.tables.first()
        assertEquals("Palma BP #4", a.label)
        assertEquals(5, a.occupiedSeats)
        assertEquals(6, a.totalSeats)
        assertEquals(10L, a.smallBlindCents)
        assertEquals(20L, a.bigBlindCents)
        assertEquals(800L, a.minBuyInCents)
        assertEquals(2000L, a.maxBuyInCents)
        assertEquals(493L, a.averagePotCents)
        assertFalse(scan.authorizesWager)
        val b = scan.tables[1]
        assertEquals(2L, b.smallBlindCents)
        assertEquals(4L, b.bigBlindCents)
        assertEquals(160L, b.minBuyInCents)
        assertEquals(400L, b.maxBuyInCents)
    }

    @Test fun holdemIsVerifiedPerRowNotFromTheTopBar() {
        val lines = screenshotOcr()
        // The actual lobby top bar says 'Кэш-Игры', not 'Холдем'.
        assertFalse(lines.filter { it.box.centerY() in 25..210 }
            .any { it.text.contains("холдем", ignoreCase = true) })
        assertEquals(4, OneWinCashLobbyParser.read(lines).tables.size)

        val withoutSecondTag = lines.filterNot {
            it.text == "Холдем NL" && it.box.top == 305
        }
        val partial = OneWinCashLobbyParser.read(withoutSecondTag)
        assertTrue(partial.headerVerified)
        assertEquals(3, partial.tables.size)
        assertTrue(partial.tables.none { it.label == "Viana MJP #5" })

        val withOmahaRow = lines.map {
            if (it.text == "Холдем NL" && it.box.top == 405)
                it.copy(text = "Омаха PL")
            else it
        }
        val mixed = OneWinCashLobbyParser.read(withOmahaRow)
        assertEquals(3, mixed.tables.size)
        assertTrue(mixed.tables.none { it.label == "Norman #2" })
    }

    @Test fun noFinancialLimitsMeansNoAutomaticChoice() {
        val scan = OneWinCashLobbyParser.read(screenshotOcr())
        assertNull(OneWinCashLobbySelector.choose(scan.tables, null))
        assertNull(OneWinCashLobbySelector.choose(scan.tables,
            OneWinCashTableLimits(0, 500)))
    }

    @Test fun selectorHonorsBlindsBuyInAndSeatsNotBigAveragePot() {
        val scan = OneWinCashLobbyParser.read(screenshotOcr())
        val low = OneWinCashLobbySelector.choose(scan.tables,
            OneWinCashTableLimits(4, 300))
        assertNotNull(low)
        assertEquals("Lubbock BP #3", low!!.table.label)
        assertEquals(200L, low.permittedBuyInCapCents)

        val noAffordable = OneWinCashLobbySelector.choose(scan.tables,
            OneWinCashTableLimits(2, 50))
        assertNull(noAffordable)
    }

    @Test fun fullTableAndMalformedMoneyNeverProduceChoice() {
        val copy = screenshotOcr().map { if (it.text == "5/6") it.copy(text = "6/6") else it }
        val full = OneWinCashLobbyParser.read(copy)
        assertNull(OneWinCashLobbySelector.choose(full.tables,
            OneWinCashTableLimits(100, 10000)))
        val wrong = screenshotOcr().map {
            if (it.text == "8$/20 $") it.copy(text = "800$/20 $") else it
        }
        assertTrue(OneWinCashLobbyParser.read(wrong).tables.none { it.label == "Palma BP #4" })
    }

    @Test fun opponentPopupNeverMatchesLobbyHeader() {
        val wrong = listOf(
            line("LadyElis", 450, 59), line("VPIP", 450, 160),
            line("PFR", 745, 160), line("43.4%", 450, 140),
            line("31.3%", 745, 140), line("Руки", 740, 295)
        )
        val r = OneWinCashLobbyParser.read(wrong)
        assertFalse(r.headerVerified)
        assertTrue(r.tables.isEmpty())
    }

    @Test fun diagnosticCountersExplainMissingHeaderWithoutExposingRawText() {
        val lines = screenshotOcr().filterNot { it.text == "Кэш-Игры" }
        val reading = OneWinCashLobbyParser.read(lines)
        val result = OneWinCashLobbyReadOnlyDiagnostics.summarize(lines, reading)
        assertTrue(result.contains("заголовок=НЕТ"))
        assertTrue(result.contains("причина=NOT_CASH_GAMES_LOBBY"))
        assertFalse(result.contains("Palma"))
        assertFalse(result.contains("Viana"))
    }

    @Test fun diagnosticCountersExposeParserGatesWithoutReducingStrictChecks() {
        val sample = screenshotOcr()
        val reading = OneWinCashLobbyParser.read(sample)
        val status = OneWinCashLobbyReadOnlyDiagnostics.summarize(sample, reading)
        assertEquals(4, reading.tables.size)
        assertTrue(status.contains("заголовок=ДА"))
        assertTrue(status.contains("метки Бай-ин=4"))
        assertTrue(status.contains("Холдем=4"))
        assertTrue(status.contains("счётчики мест=4"))
        assertTrue(status.contains("кандидатов=4"))
        assertFalse(status.contains("Lubbock"))
    }

}
