package com.example.airemote

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class PlayerBringUpTest {
    private fun stable(hole: List<String>, board: List<String>,
                       epoch: Long = 4L, sequence: Long = 10L) =
        StableCardReading(1000, epoch, sequence, hole, board, 0.95)

    @Test fun inspectCorrectlyRecognizedHandWithoutClaimingActionAuthority() {
        val result = PlayerHandEvaluator.inspect(stable(
            listOf("Ah", "Kh"), listOf("Qh", "Jh", "Th")))
        assertNotNull(result)
        assertEquals(HandStage.FLOP, result!!.stage)
        assertEquals(PokerHandClass.STRAIGHT_FLUSH, result.bestMadeHand)
        assertFalse(result.actionAuthorized)
    }

    @Test fun emptyBoardIsPreflopAndNoHandClassOrGamePermission() {
        val preview = PlayerHandEvaluator.inspect(stable(listOf("Ah", "Ad"), emptyList()))
        assertEquals(HandStage.PREFLOP, preview!!.stage)
        assertNull(preview.bestMadeHand)
        assertFalse(preview.actionAuthorized)
    }

    @Test fun duplicateCardsAndMismatchedShapesFailClosed() {
        assertNull(PlayerHandEvaluator.inspect(stable(listOf("Ah", "Ad"),
            listOf("Ah", "Jc", "Ts"))))
        assertNull(PlayerHandEvaluator.inspect(stable(listOf("Ah"), listOf("Jc", "Ts", "9d"))))
        assertNull(PlayerHandEvaluator.inspect(stable(listOf("Ah", "Ad"),
            listOf("2h", "3c"))))
    }

    @Test fun reportExplicitlyExplainsWhyAutotapIsNotReady() {
        val quality = JSONObject().put("frames_inspected", 21)
            .put("two_frame_agreements", 0)
        val checks = JSONObject()
            .put("in_player_mode", true).put("operator_stop", false)
            .put("capture_active", true)
            .put("accessibility_connected", true)
            .put("platform_permission", true)
            .put("independent_state_provider", false)
        val report = PlayerEngineeringReport.build(quality, checks,
            JSONObject(), "code80", 36)
        assertFalse(report.getBoolean("ready_for_automated_taps"))
        val blockers = report.getJSONArray("blockers")
        val codes = (0 until blockers.length()).map {
            blockers.getJSONObject(it).getString("code")
        }
        assertTrue(codes.contains("TRUSTED_GAME_STATE_SOURCE"))
        assertTrue(codes.contains("PLAYER_CARD_CONSENSUS"))
        assertTrue(codes.contains("CONTROL_GEOMETRY"))
        assertFalse(report.getJSONObject("safety").getBoolean("automatic_touch_enabled_by_report"))
        assertFalse(report.getJSONObject("safety").getBoolean("reports_sent_to_cloud"))
    }

    @Test fun playerReportNeedsPlatformConsent() {
        val report = PlayerEngineeringReport.build(
            JSONObject().put("frames_inspected", 3).put("two_frame_agreements", 2),
            JSONObject().put("in_player_mode", true).put("operator_stop", false)
                .put("capture_active", true).put("accessibility_connected", true)
                .put("platform_permission", false).put("independent_state_provider", true)
                .put("confirmed_stage", true).put("verified_own_turn", true)
                .put("exact_action_labels", true).put("verified_equity", true),
            JSONObject(), "v", 36)
        val codes = report.getJSONArray("blockers")
        assertTrue((0 until codes.length()).any {
            codes.getJSONObject(it).getString("code") == "PLATFORM_AUTOMATION"
        })
    }
}
