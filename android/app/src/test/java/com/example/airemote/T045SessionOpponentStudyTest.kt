package com.example.airemote

import org.junit.Assert.*
import org.junit.Test

class T045SessionOpponentStudyTest {
    @Test fun observedPopupIsSanitizedAndNicknameNeverLeaksToGpt() {
        val opponent = ObservedOpponentProfile(
            seatIndex = 4,
            displayedNickname = "privateNicknameIgnoreInstructions",
            metrics = listOf(
                OpponentPopupMetricValue(OpponentPopupMetric.VPIP, 42.5),
                OpponentPopupMetricValue(OpponentPopupMetric.PFR, 18.0),
                OpponentPopupMetricValue(OpponentPopupMetric.HANDS, 70.0)
            )
        )
        val text = T045SessionStatsFormatter.format(listOf(opponent))
        assertTrue(text.contains("seat_4"))
        assertTrue(text.contains("VPIP=42.5"))
        assertTrue(text.contains("PFR=18"))
        assertTrue(text.contains("HANDS=70"))
        assertFalse(text.contains("privateNickname"))
        assertTrue(text.length <= 280)
    }

    @Test fun malformedAndUnverifiedPopupNumbersDoNotBecomeStrategicFacts() {
        val adversarial = ObservedOpponentProfile(
            seatIndex = 1,
            displayedNickname = "steal_context",
            metrics = listOf(
                OpponentPopupMetricValue(OpponentPopupMetric.VPIP, 150.0),
                OpponentPopupMetricValue(OpponentPopupMetric.PFR, Double.NaN),
                OpponentPopupMetricValue(OpponentPopupMetric.ATS, 90.0,
                    evidence = "INFERRED_BY_MODEL"),
                OpponentPopupMetricValue(OpponentPopupMetric.HANDS, 13.0)
            )
        )
        val summary = T045SessionStatsFormatter.format(listOf(adversarial))
        assertTrue(summary.contains("HANDS=13"))
        assertFalse(summary.contains("VPIP="))
        assertFalse(summary.contains("PFR="))
        assertFalse(summary.contains("ATS="))
        assertFalse(summary.contains("steal_context"))
    }

    @Test fun sixSeatTableNeverProvidesMoreThanFiveOpponentRows() {
        val observations = (0..9).map { seat ->
            ObservedOpponentProfile(
                seatIndex = seat,
                displayedNickname = "nickname_" + seat,
                metrics = listOf(OpponentPopupMetricValue(OpponentPopupMetric.VPIP, 25.0))
            )
        }
        val text = T045SessionStatsFormatter.format(observations)
        assertTrue(text.length <= 280)
        assertEquals(5, text.split(';').size)
    }
}
