package com.example.airemote

import org.junit.Assert.*
import org.junit.Test

class T045SessionStatsFormatterTest {
    @Test fun preservesAllNumericOcrMetricsButNoNicknameOrWalletData() {
        val metrics = listOf(
            OpponentPopupMetricValue(OpponentPopupMetric.VPIP, 43.1),
            OpponentPopupMetricValue(OpponentPopupMetric.PFR, 17.0),
            OpponentPopupMetricValue(OpponentPopupMetric.ATS, 29.0),
            OpponentPopupMetricValue(OpponentPopupMetric.THREE_BET, 4.5),
            OpponentPopupMetricValue(OpponentPopupMetric.C_BET, 67.0),
            OpponentPopupMetricValue(OpponentPopupMetric.FOLD_TO_C_BET, 50.0),
            OpponentPopupMetricValue(OpponentPopupMetric.CHECK_RAISE, 8.0),
            OpponentPopupMetricValue(OpponentPopupMetric.WTSD, 32.0),
            OpponentPopupMetricValue(OpponentPopupMetric.WSD, 51.0),
            OpponentPopupMetricValue(OpponentPopupMetric.AGGRESSION_FACTOR, 2.4),
            OpponentPopupMetricValue(OpponentPopupMetric.HANDS, 127.0))
        val subject = ObservedOpponentProfile(
            2, "REAL_NICK_WITH_PRIVATE_DATA", metrics)
        val full = T045SessionStatsFormatter.formatForHandoff(listOf(subject))
        assertTrue(full.contains("seat_2:VPIP=43.1"))
        assertTrue(full.contains("HANDS=127"))
        assertTrue(full.contains("AGGRESSION_FACTOR=2.4"))
        assertFalse(full.contains("REAL_NICK"))
        assertTrue(full.length <= 900)
        assertNotNull(ChatGptPlanHandoffProtocol.prepare(
            full, "test_nonce_for_plan_handoff_123"))
    }

    @Test fun rejectsUnverifiedAndImpossibleMetricsBeforeCloudTransport() {
        val profile = ObservedOpponentProfile(1,"NeverSendThis",listOf(
            OpponentPopupMetricValue(OpponentPopupMetric.VPIP, 19.0),
            OpponentPopupMetricValue(OpponentPopupMetric.VPIP, 33.0),
            OpponentPopupMetricValue(OpponentPopupMetric.PFR, 120.0),
            OpponentPopupMetricValue(OpponentPopupMetric.ATS, 5.0,"NOT_OCR"),
            OpponentPopupMetricValue(OpponentPopupMetric.HANDS, Double.NaN)))
        val text = T045SessionStatsFormatter.formatForHandoff(listOf(profile))
        assertEquals("seat_1:VPIP=19", text)
        assertNotNull(ChatGptPlanHandoffProtocol.prepare(
            text, "test_nonce_for_plan_handoff_123"))
        assertFalse(text.contains("NeverSendThis"))
    }

    @Test fun boundedSerializerNeverSplitsIndividualMetricOrSeat() {
        val profiles = (0..5).map { seat ->
            ObservedOpponentProfile(
                seat, "name_" + seat,
                OpponentPopupMetric.entries.map {
                    OpponentPopupMetricValue(it, if (it == OpponentPopupMetric.HANDS)
                        123.0 else 40.0)
                })
        }
        val full = T045SessionStatsFormatter.formatForHandoff(profiles)
        val short = T045SessionStatsFormatter.format(profiles)
        assertTrue(full.length <= 900)
        assertTrue(short.length <= 280)
        assertFalse(short.endsWith(","))
        assertFalse(short.endsWith(":"))
        assertFalse(full.endsWith(","))
        assertFalse(full.endsWith(":"))
        assertNotNull(ChatGptPlanHandoffProtocol.prepare(
            full, "test_nonce_for_plan_handoff_123"))
        assertFalse(full.contains("name_"))
    }
}
