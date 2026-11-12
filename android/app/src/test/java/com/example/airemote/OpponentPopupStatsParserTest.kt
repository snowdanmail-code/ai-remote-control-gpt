package com.example.airemote

import org.junit.Assert.*
import org.junit.Test

class OpponentPopupStatsParserTest {
    private fun ocr(text: String, left: Int = 250, top: Int = 300,
                    right: Int = 520, bottom: Int = 329) =
        PositionedOcrLine(text, NormalizedTextBox(left, top, right, bottom))

    @Test fun readsOnlyLabelsActuallyVisibleInPopup() {
        val found = OpponentPopupStatsParser.parse(listOf(
            ocr("VPIP: 26%", top = 300, bottom = 329),
            ocr("PFR 18%", top = 360, bottom = 389),
            ocr("3-BET 7.5%", top = 410, bottom = 439),
            ocr("AF: 2,4", top = 470, bottom = 499),
            ocr("HANDS 215", top = 540, bottom = 569)
        )).associate { it.metric to it.amount }
        assertEquals(26.0, found[OpponentPopupMetric.VPIP] ?: -1.0, 0.001)
        assertEquals(18.0, found[OpponentPopupMetric.PFR] ?: -1.0, 0.001)
        assertEquals(7.5, found[OpponentPopupMetric.THREE_BET] ?: -1.0, 0.001)
        assertEquals(2.4, found[OpponentPopupMetric.AGGRESSION_FACTOR] ?: -1.0, 0.001)
        assertEquals(215.0, found[OpponentPopupMetric.HANDS] ?: -1.0, 0.001)
    }

    @Test fun acceptsAdjacentOcrLabelAndNumberOnlyInSameRow() {
        val found = OpponentPopupStatsParser.parse(listOf(
            ocr("VPIP", 200, 350, 280, 374),
            ocr("35%", 345, 350, 420, 374),
            ocr("PFR", 200, 430, 280, 454),
            ocr("17%", 345, 430, 420, 454)
        ))
        assertEquals(2, found.size)
        assertEquals(35.0, found.single { it.metric == OpponentPopupMetric.VPIP }.amount, 0.001)
        assertEquals(17.0, found.single { it.metric == OpponentPopupMetric.PFR }.amount, 0.001)
    }

    @Test fun rejectsInventedAmbiguousOrPhysicallyImpossiblePercentages() {
        val found = OpponentPopupStatsParser.parse(listOf(
            ocr("VPIP: 500%", top = 300, bottom = 329),
            ocr("PFR 17%", top = 400, bottom = 429),
            ocr("PFR 18%", top = 460, bottom = 489),
            ocr("3BET 103%", top = 520, bottom = 549),
            ocr("AF -1", top = 580, bottom = 609),
            ocr("unknown 41%", top = 640, bottom = 669)
        ))
        assertTrue(found.isEmpty())
    }

    @Test fun playerNameAloneNeverCreatesStatisticsOrIdentity() {
        val found = OpponentPopupStatsParser.parse(listOf(
            ocr("Opponent Gamer69"),
            ocr("38%", 540, 350, 620, 380),
            ocr("Bank 450", 540, 420, 620, 449)
        ))
        assertTrue(found.isEmpty())
    }

    @Test fun doesNotMixMisalignedFieldsAndResultsNeverAuthorizeTaps() {
        val found = OpponentPopupStatsParser.parse(listOf(
            ocr("VPIP", 200, 350, 280, 374),
            ocr("36%", 350, 480, 420, 505)
        ))
        assertTrue(found.isEmpty())
        val good = OpponentPopupStatsParser.parse(listOf(ocr("VPIP 36%")))
        assertEquals("POPUP_OCR_ONLY", good.single().evidence)
    }
    @Test fun authenticOneWinPopupLayoutNumbersAreAboveRussianLabels() {
        // Scaled 354x768 screenshots supplied by the owner: right popup,
        // 2 columns and four rows. The coordinates are OCR test fixtures,
        // NOT Android gesture coordinates or proof of a player identity.
        fun line(s: String, x: Int, y: Int) =
            PositionedOcrLine(s, NormalizedTextBox(x, y, x + 110, y + 18))
        val text = listOf(
            line("43.4%", 470, 130), line("31.3%", 765, 130),
            line("VPIP", 470, 151), line("PFR", 765, 151),
            line("37.9%", 470, 184), line("14.2%", 765, 184),
            line("ATS", 470, 205), line("3-Бет", 765, 205),
            line("40%", 470, 238), line("50%", 765, 238),
            line("Контбет", 470, 259), line("Фолд на контбет", 735, 259),
            line("0%", 470, 290), line("99", 765, 290),
            line("Чек-рейз", 470, 311), line("Руки", 765, 311)
        )
        val result = OpponentPopupStatsParser.parse(text).associate { it.metric to it.amount }
        assertEquals(8, result.size)
        assertEquals(43.4, result[OpponentPopupMetric.VPIP] ?: -1.0, 0.0001)
        assertEquals(31.3, result[OpponentPopupMetric.PFR] ?: -1.0, 0.0001)
        assertEquals(37.9, result[OpponentPopupMetric.ATS] ?: -1.0, 0.0001)
        assertEquals(14.2, result[OpponentPopupMetric.THREE_BET] ?: -1.0, 0.0001)
        assertEquals(40.0, result[OpponentPopupMetric.C_BET] ?: -1.0, 0.0001)
        assertEquals(50.0, result[OpponentPopupMetric.FOLD_TO_C_BET] ?: -1.0, 0.0001)
        assertEquals(0.0, result[OpponentPopupMetric.CHECK_RAISE] ?: -1.0, 0.0001)
        assertEquals(99.0, result[OpponentPopupMetric.HANDS] ?: -1.0, 0.0001)
    }

    @Test fun noCrossColumnAssignmentWhenValueIsUnderAnotherStat() {
        fun at(s: String, x: Int, y: Int) =
            PositionedOcrLine(s, NormalizedTextBox(x, y, x + 50, y + 18))
        val result = OpponentPopupStatsParser.parse(listOf(
            at("VPIP", 430, 175),
            at("PFR", 800, 175),
            at("28%", 800, 150) // ONLY right column has a value
        ))
        assertEquals(1, result.size)
        assertEquals(OpponentPopupMetric.PFR, result.single().metric)
    }

    @Test fun firstSuppliedOneWinScreenshotHasEightDistinctReadings() {
        fun line(text: String, x: Int, y: Int) =
            PositionedOcrLine(text, NormalizedTextBox(x, y, x + 110, y + 18))
        val lines = listOf(
            line("25%", 475, 132), line("25%", 758, 132),
            line("VPIP", 475, 153), line("PFR", 758, 153),
            line("0%", 475, 186), line("0%", 758, 186),
            line("ATS", 475, 207), line("3-Бет", 758, 207),
            line("0%", 475, 240), line("0%", 758, 240),
            line("Контбет", 475, 261), line("Фолд на контбет", 728, 261),
            line("0%", 475, 292), line("8", 758, 292),
            line("Чек-рейз", 475, 313), line("Руки", 758, 313)
        )
        val stats = OpponentPopupStatsParser.parse(lines).associate { it.metric to it.amount }
        assertEquals(8, stats.size)
        assertEquals(25.0, stats[OpponentPopupMetric.VPIP] ?: -1.0, 0.01)
        assertEquals(25.0, stats[OpponentPopupMetric.PFR] ?: -1.0, 0.01)
        assertEquals(8.0, stats[OpponentPopupMetric.HANDS] ?: -1.0, 0.01)
    }

    @Test fun nicknameComesOnlyFromHeaderNotTableOrStats() {
        val first = listOf(
            ocr("krcv", 480, 60, 590, 85),
            ocr("VPIP", 480, 155, 550, 175),
            ocr("LadyElis", 480, 600, 600, 625))
        assertEquals("krcv", OpponentPopupStatsParser.displayedNickname(first))
        assertEquals("LadyElis", OpponentPopupStatsParser.displayedNickname(listOf(
            ocr("LadyElis", 480, 56, 610, 82))))
        assertNull(OpponentPopupStatsParser.displayedNickname(listOf(
            ocr("116 $", 480, 60, 590, 85), ocr("VPIP", 480, 150, 590, 173))))
    }

}
