package com.example.airemote

import android.content.Context
import android.os.Build
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

/** A deployment/readiness report for PLAYER mode. A green card-recognition
 *  statistic never becomes proof of an authorized action.
 *  No screenshot, account, table ID, opponent or OAuth data exported.
 */
object PlayerEngineeringReport {
    const val SCHEMA = "t042-player-readiness-v1"

    fun build(vision: JSONObject, current: JSONObject, incidents: JSONObject,
              version: String, androidApi: Int): JSONObject {
        val checks = vision.optLong("frames_inspected")
        val twoFrame = vision.optLong("two_frame_agreements")
        val blockers = JSONArray()
        fun block(id: String, severity: String, status: String, remedy: String) {
            blockers.put(JSONObject().put("code", id).put("severity", severity)
                .put("status", status).put("next_step", remedy))
        }
        if (!current.optBoolean("in_player_mode", false))
            block("PLAYER_MODE_OFF", "P1", "NOT_READY",
                "Select PLAYER with STOP active and capture inactive")
        if (current.optBoolean("operator_stop", true))
            block("STOP_ENGAGED", "P1", "DISARMED",
                "Operator must explicitly authorize a new safe session")
        if (!current.optBoolean("capture_active", false))
            block("MEDIA_PROJECTION", "P1", "NO_CAPTURE",
                "Start an operator-approved screen capture on Samsung")
        if (!current.optBoolean("accessibility_connected", false))
            block("ACCESSIBILITY", "P1", "DISCONNECTED",
                "Enable the accessibility service and verify active root")
        if (!current.optBoolean("platform_permission", false))
            block("PLATFORM_AUTOMATION", "P1", "NOT_VERIFIED",
                "Verify that this virtual-chip app permits automation and authorize target package")
        if (!current.optBoolean("independent_state_provider", false))
            block("TRUSTED_GAME_STATE_SOURCE", "P1", "NOT_CONNECTED",
                "Integrate an independently authenticated table, hand and turn source; never infer identity from OCR")
        if (checks == 0L)
            block("PLAYER_CARD_INPUT", "P1", "NO_READINGS",
                "Observe full-screen player mode with two private cards and community card ROIs")
        else if (twoFrame == 0L)
            block("PLAYER_CARD_CONSENSUS", "P1", "NO_TWO_FRAME_AGREEMENT",
                "Annotate real captured private and public cards, then retest rank/suit reader")
        if (!current.optBoolean("confirmed_stage", false))
            block("CONFIRMED_HAND_STAGE", "P2", "UNAVAILABLE",
                "Require the same authenticated hand and a current legal stage")
        if (!current.optBoolean("verified_own_turn", false))
            block("OWN_TURN", "P1", "UNVERIFIED",
                "Do not act on bright buttons alone: confirm turn identity and legal actions")
        if (!current.optBoolean("exact_action_labels", false))
            block("CONTROL_GEOMETRY", "P1", "UNVERIFIED",
                "Calibrate exact current button labels and bounds with independent checks")
        if (!current.optBoolean("verified_equity", false))
            block("INDEPENDENT_EQUITY", "P2", "UNAVAILABLE",
                "Local hand class is not equity; bind verified pot odds and opponent model to a single turn")
        val ready = blockers.length() == 0
        val out = JSONObject()
        out.put("schema", SCHEMA)
            .put("build_version", version.take(64).filter {
                it.isLetterOrDigit() || it in ".-_"
            })
            .put("android_api", androidApi)
            .put("local_player_vision", vision)
            .put("capture_and_automation_checks", current)
            .put("blockers", blockers)
            .put("ready_for_automated_taps", ready)
            .put("safety", JSONObject()
                .put("two_frame_agreement_is_not_identity", true)
                .put("card_hand_class_is_not_equity", true)
                .put("spectator_cloud_quota_does_not_limit_player", true)
                .put("automatic_touch_enabled_by_report", false)
                .put("reports_sent_to_cloud", false))
            .put("bounded_incidents", incidentDigest(incidents))
        return out
    }

    private fun incidentDigest(report: JSONObject): JSONObject {
        val source = report.optJSONArray("events") ?: JSONArray()
        val byCode = JSONObject()
        var considered = 0
        for (i in 0 until source.length()) {
            val code = source.optJSONObject(i)?.optString("code").orEmpty()
            if (!Regex("^[A-Z_]{1,60}$").matches(code)) continue
            byCode.put(code, byCode.optInt(code) + 1)
            considered++
        }
        return JSONObject().put("count", considered).put("by_code", byCode)
    }

    private fun liveChecks(context: Context): JSONObject {
        val now = SystemClock.elapsedRealtime()
        val recent = FrameStore.cardConsensus.current(now)
        val turn = FrameStore.turnConsensus.current(now)
        return JSONObject()
            .put("in_player_mode", !CaptureObservationMode.isSpectator())
            .put("operator_stop", OperatorStopStore.isStopped(context))
            .put("capture_active", FrameStore.isCapturing)
            .put("accessibility_connected", LiveForegroundProbe.connected())
            .put("platform_permission", LiveGamePermissionGate.permitted())
            .put("independent_state_provider", OnDeviceAutonomousRuntime.verifiedSourceConfigured())
            .put("verified_cards_on_current_frame", recent != null)
            .put("confirmed_stage", OnDeviceHandState.snapshot(now).trustworthyNow)
            .put("verified_own_turn", turn != null && turn.sourceVerified &&
                turn.state == TurnDisplayState.READY)
            .put("exact_action_labels", false)
            .put("verified_equity", false)
            .put("automation_state", OnDeviceAutonomousRuntime.status().state.name)
    }

    fun export(context: Context): String {
        val version = try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
                .versionName ?: "unknown"
        } catch (_: Exception) { "unknown" }
        return build(PlayerVisionJournal.snapshot(context),
            liveChecks(context), JSONObject(OnDeviceIncidentRecorder.exportReport(context)),
            version, Build.VERSION.SDK_INT).toString(2)
    }

    fun brief(context: Context): String = try {
        val report = JSONObject(export(context))
        val blockers = report.getJSONArray("blockers")
        val source = report.getJSONObject("local_player_vision")
        val names = (0 until blockers.length()).take(9).map {
            blockers.getJSONObject(it).getString("code")
        }
        "ИИ-ИГРОК — диагностика подключения:\n" +
            "Распознано кадров: ${source.optLong("frames_inspected")}; " +
            "совпало на 2 кадрах: ${source.optLong("two_frame_agreements")}.\n" +
            "Преград к автодействиям: ${blockers.length()}. " +
            "Коды: ${names.joinToString(", ")}.\n" +
            "Текущий анализ карт НЕ разрешает касания. " +
            "Сохрани JSON для инженерного исправления."
    } catch (_: Exception) {
        "Отчёт ИИ-игрока временно недоступен. Аварийный STOP сохраняется."
    }
}
