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
}
