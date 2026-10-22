package com.example.airemote

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*

class SpectatorEngineeringReportTest {
    private fun board(cards: List<String>, seq: Long = 1, epoch: Long = 1,
                      pkg: String = "test.game") =
        StableSpectatorBoard(cards, epoch, seq, seq * 1000, pkg)

    @Test fun timelineNeverClaimsVerifiedHandEnd() {
        val timeline = SpectatorBoardTimeline()
        assertEquals(SpectatorBoardEvent.FIRST_VISIBLE_FLOP,
            timeline.observe(board(listOf("Ah", "Td", "3c"))))
        assertEquals(SpectatorBoardEvent.STABLE_UNCHANGED,
            timeline.observe(board(listOf("Ah", "Td", "3c"), 2)))
        assertEquals(SpectatorBoardEvent.POSSIBLE_FLOP_TO_TURN,
            timeline.observe(board(listOf("Ah", "Td", "3c", "5s"), 3)))
        assertEquals(SpectatorBoardEvent.POSSIBLE_TURN_TO_RIVER,
            timeline.observe(board(listOf("Ah", "Td", "3c", "5s", "Jh"), 4)))
        assertEquals(SpectatorBoardEvent.POSSIBLE_NEW_HAND,
            timeline.observe(board(listOf("2h", "6d", "9c"), 5)))
        assertEquals(SpectatorBoardEvent.FIRST_VISIBLE_FLOP,
            timeline.observe(board(listOf("2h", "6d", "9c"), 1, 2)))
    }

    @Test fun shadowComparisonSeparatesAgreementAndGroundTruth() {
        fun r(vararg c: String?) = VisibleCommunityReading(c.toList(), c.all { it != null }, "")
        assertEquals(SpectatorShadowVerdict.AGREE,
            SpectatorShadowComparison.compare(r("Ah", "Td", "3c"), r("Ah", "Td", "3c")))
        assertEquals(SpectatorShadowVerdict.DISAGREE,
            SpectatorShadowComparison.compare(r("Ah", "Td", "3c"), r("Ah", "Td", "4c")))
        assertEquals(SpectatorShadowVerdict.INCOMPLETE,
            SpectatorShadowComparison.compare(r("Ah", null, "3c"), r("Ah", "Td", "3c")))
        assertEquals(SpectatorShadowVerdict.INVALID,
            SpectatorShadowComparison.compare(r("Ah", "Ah", "3c"), r("Ah", "Td", "3c")))
    }

    @Test fun realPreviousReportGeneratesActionableDiagnosisWithoutFakeAccuracy() {
        val observations = JSONObject()
            .put("inspections", 21).put("no_visible_board", 10)
            .put("uncertain", 10).put("single_frame_candidates", 1)
            .put("two_frame_agreements", 0)
            .put("operator_confirmed", 0).put("operator_corrected", 0)
            .put("examples", JSONArray())
        val incidents = JSONObject().put("events", JSONArray())
        val report = SpectatorEngineeringReport.build(observations, incidents,
            "2.14.41-code79-test", 36, 1000L)
        val kpi = report.getJSONObject("quality_kpis")
        assertEquals(21L, kpi.getLong("frame_inspections"))
        assertEquals("UNVERIFIED_NO_GROUND_TRUTH", kpi.getString("readiness"))
        assertTrue(kpi.isNull("reviewed_agreement_percent_not_accuracy"))
        val issues = report.getJSONArray("prioritized_issues")
        val ids = (0 until issues.length()).map { issues.getJSONObject(it).getString("issue_id") }
        assertTrue(ids.contains("T042-DIAG-002"))
        assertTrue(ids.contains("T042-DIAG-003"))
        assertTrue(ids.contains("T042-DIAG-004"))
        assertTrue(ids.contains("T042-DIAG-005"))
        assertFalse(report.getJSONObject("assurance").getBoolean("player_strategy_modified_by_report"))
        assertFalse(report.getJSONObject("assurance").getBoolean("spectator_gestures_allowed"))
        assertFalse(report.getJSONObject("provenance").getBoolean("images_included"))
    }

    @Test fun incidentSummariesCannotLeakArbitraryFieldsIntoQualityNumbers() {
        val evs = JSONArray().put(JSONObject().put("code", "OCR_CONFLICT")
            .put("severity", "WARNING")
            .put("some_unknown_sensitive_value", "never export from untrusted source"))
        val report = SpectatorEngineeringReport.build(
            JSONObject().put("inspections", 1), JSONObject().put("events", evs),
            "code79", 36, 1000L)
        val issues = report.getJSONArray("prioritized_issues")
        assertTrue((0 until issues.length()).any {
            issues.getJSONObject(it).getString("issue_id") == "T042-INC-OCR_CONFLICT"
        })
    }
}
