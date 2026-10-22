package com.example.airemote

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/**
 * T042 engineering handoff. Local, bounded and privacy-filtered:
 * no screenshots, OCR sentences, opponent names, network uploads or secrets.
 * Evidence never authorizes a poker decision or changes any live model.
 */
object SpectatorEngineeringReport {
    const val SCHEMA = "t042-engineering-diagnostics-v1"

    /** Derived diagnosis uses only countable evidence. Never calls match accuracy
     * "verified" without a reference label. */
    fun diagnose(observations: JSONObject, incidentReport: JSONObject): JSONArray {
        val issues = JSONArray()
        val inspected = observations.optLong("inspections")
        val unknown = observations.optLong("uncertain")
        val none = observations.optLong("no_visible_board")
        val stable = observations.optLong("two_frame_agreements")
        val verified = observations.optLong("operator_confirmed") +
            observations.optLong("operator_corrected")
        val corrections = observations.optLong("operator_corrected")
        val performance = observations.optJSONObject("performance") ?: JSONObject()
        val numeric = observations.optJSONObject("numeric_ocr") ?: JSONObject()
        val timing = performance.optLong("sample_count")
        val expensive = performance.optLong("latency_over_1500")
        val incidentEvents = incidentReport.optJSONArray("events") ?: JSONArray()
        val errors = mutableMapOf<String, Int>()
        for (i in 0 until incidentEvents.length()) {
            val event = incidentEvents.optJSONObject(i) ?: continue
            val code = event.optString("code")
            if (code in setOf("CAPTURE_FRAME_ERROR", "CAPTURE_PERMISSION_ERROR",
                    "OCR_ENGINE_ERROR", "OCR_CONFLICT", "OCR_STALE")) {
                errors[code] = (errors[code] ?: 0) + 1
            }
        }
        fun add(id: String, priority: String, category: String, count: Long,
                conclusion: String, hypotheses: String, next: String,
                acceptance: String) {
            issues.put(JSONObject()
                .put("issue_id", id).put("priority", priority)
                .put("category", category).put("evidence_count", count)
                .put("conclusion", conclusion)
                .put("possible_causes_not_proven", hypotheses)
                .put("recommended_next_step", next)
                .put("acceptance_check", acceptance))
        }
        if (inspected == 0L) add("T042-DIAG-001", "P1", "CAPTURE_VISIBILITY", 0,
            "No published spectator card checks in this report",
            "Capture stopped, STOP active, wrong foreground, or no qualifying frame",
            "Confirm spectator mode and a live fullscreen table; review capture incident codes",
            "At least one valid observer-only frame is recorded; zero gameplay gestures")
        if (inspected > 0 && stable == 0L)
            add("T042-DIAG-002", "P1", "BOARD_CONSENSUS", inspected,
                "Zero two-frame agreements; accuracy cannot be inferred",
                "Card region misaligned, unstable classification, short visibility, or game stage changes",
                "Capture one consented, manually cropped board sample and annotate visible cards",
                "Two consecutive fresh matching observations of a labeled 3/4/5-card board")
        if (inspected >= 10 && unknown * 2 >= inspected)
            add("T042-DIAG-003", "P1", "CARD_OCR", unknown,
                "At least half the checks are incomplete or ambiguous",
                "Nominal/suit glyph classifier, scaling or board ROI may be inaccurate",
                "Compare source screenshot with saved candidate and verified manual label; test a separate reader version",
                "Measure completion and correction rates on held-out human-labeled examples")
        if (inspected >= 10 && none * 3 >= inspected)
            add("T042-DIAG-004", "P2", "BOARD_VISIBILITY", none,
                "Many checks contained no identified community board; this is not necessarily an error",
                "Preflop, new hand, hidden table, or missed board area",
                "Distinguish preflop from occluded/unknown frames before adjusting geometry",
                "Preflop reliably stays empty; visible flop has three annotated public cards")
        if (numeric.optLong("processed_frames") >= 8 &&
            numeric.optLong("pot_candidates") == 0L)
            add("T042-DIAG-008", "P2", "SPECTATOR_POT_OCR",
                numeric.optLong("processed_frames"),
                "Public pot OCR has zero candidates in the observed frames",
                "Pot label may be absent, unreadable, or outside the text parser's region",
                "Inspect a consented, redacted pot-label screenshot and calibrated ROI",
                "Repeatable pot candidate on independently annotated images")
        if (verified == 0L)
            add("T042-DIAG-005", "P1", "GROUND_TRUTH", 0,
                "No recognized boards were manually confirmed or corrected",
                "Human feedback not yet collected",
                "Collect labeled samples; never treat agreeing frames as correctness proof",
                "At least 20 human-reviewed public-board examples including difficult cases")
        if (corrections > 0L)
            add("T042-DIAG-006", "P1", "LABEL_MISMATCH", corrections,
                "Human operator corrected one or more recognized community boards",
                "Actual card glyph mismatch, mistaken human entry, or stale observation",
                "Reconcile corrected board with source image; preserve both candidate and label",
                "A second review and regression tests pass before classifier promotion")
        if (timing >= 10 && expensive * 5 >= timing)
            add("T042-DIAG-007", "P2", "PERFORMANCE", expensive,
                "At least one fifth of measured observations exceeded 1500 ms",
                "Image scaling, low memory, thermal throttling, or disk I/O",
                "Profile preprocessing, pixel copy, journal writes and queue length on Samsung",
                "95th percentile local observation time remains within the chosen device budget")
        for (code in listOf("CAPTURE_FRAME_ERROR", "CAPTURE_PERMISSION_ERROR",
                            "OCR_ENGINE_ERROR", "OCR_CONFLICT", "OCR_STALE")) {
            val count = errors[code] ?: 0
            if (count > 0) add("T042-INC-$code",
                if (code == "CAPTURE_FRAME_ERROR" || code == "CAPTURE_PERMISSION_ERROR") "P1" else "P2",
                "GLOBAL_INCIDENT_LOG", count.toLong(),
                "Incident code observed: $code. Could originate outside spectator mode",
                "See event capture epoch and affected component; no source-frame proof in the log",
                "Reproduce with a timestamped test; compare event epoch with spectator session",
                "Relevant code no longer repeats under the same test conditions")
        }
        return issues
    }

    private fun incidentSummary(incidents: JSONObject): JSONObject {
        val src = incidents.optJSONArray("events") ?: JSONArray()
        val counts = JSONObject()
        val redactedEvents = JSONArray()
        var warning = 0
        var error = 0
        for (i in 0 until src.length()) {
            val event = src.optJSONObject(i) ?: continue
            val code = event.optString("code", "UNKNOWN")
            if (!Regex("^[A-Z_]{1,60}$").matches(code)) continue
            // Reconstruct events from allow-listed, typed fields. An unknown
            // field (including attacker text) must never leave the device.
            val cleaned = JSONObject()
            cleaned.put("code", code)
            val severity = event.optString("severity")
            if (severity in setOf("INFO", "WARNING", "ERROR", "CRITICAL"))
                cleaned.put("severity", severity)
            for (field in listOf("sequence", "elapsed_ms", "capture_epoch",
                    "frame_sequence", "latency_ms", "pot_chips", "call_chips",
                    "timer_remaining_ms")) {
                val value = event.opt(field)
                if (value is Number && value.toLong() >= 0L)
                    cleaned.put(field, value.toLong())
            }
            val stage = event.optString("stage")
            if (stage in setOf("PREFLOP", "FLOP", "TURN", "RIVER", "SHOWDOWN", "UNKNOWN"))
                cleaned.put("stage", stage)
            val action = event.optString("action")
            if (action in setOf("FOLD", "CHECK", "CALL", "BET", "RAISE", "CONFIRM"))
                cleaned.put("action", action)
            redactedEvents.put(cleaned)
            counts.put(code, counts.optInt(code) + 1)
            if (event.optString("severity") == "WARNING") warning++
            if (event.optString("severity") in setOf("ERROR", "CRITICAL")) error++
        }
        return JSONObject()
            .put("total_bounded_events", src.length())
            .put("warnings", warning)
            .put("errors_and_critical", error)
            .put("by_code", counts)
            .put("events", redactedEvents)
    }

    fun build(observations: JSONObject, incidents: JSONObject,
              version: String, androidApiLevel: Int, createdAtMs: Long): JSONObject {
        val issues = diagnose(observations, incidents)
        val inspections = observations.optLong("inspections")
        val complete = observations.optLong("single_frame_candidates") +
            observations.optLong("two_frame_agreements")
        val confirmed = observations.optLong("operator_confirmed")
        val corrected = observations.optLong("operator_corrected")
        val reviewed = confirmed + corrected
        val perf = observations.optJSONObject("performance") ?: JSONObject()
        val avgMs = if (perf.optLong("sample_count") > 0)
            perf.optLong("processing_ms_sum") / perf.optLong("sample_count") else -1L

        val readiness = when {
            inspections == 0L -> "NO_SPECTATOR_FRAMES"
            reviewed == 0L -> "UNVERIFIED_NO_GROUND_TRUTH"
            reviewed < 20L -> "LIMITED_GROUND_TRUTH"
            else -> "NEEDS_INDEPENDENT_HOLDOUT_EVALUATION"
        }
        val report = JSONObject()
        report.put("schema", SCHEMA)
            .put("created_at_ms", createdAtMs)
            .put("provenance", JSONObject()
                .put("app_version", version.take(70).filter { it.isLetterOrDigit() || it in ".-_" })
                .put("android_api", androidApiLevel)
                .put("source", "ON_DEVICE_SPECTATOR_JOURNAL_AND_BOUNDED_INCIDENTS")
                .put("images_included", false)
                .put("ocr_text_included", false)
                .put("opponent_identity_included", false)
                .put("auto_upload", false))
            .put("assurance", JSONObject()
                .put("stage_events_are_inferences", true)
                .put("two_frame_agreement_is_not_accuracy", true)
                .put("manual_labels_are_unverified_by_independent_reviewer", true)
                .put("cloud_spectator_telemetry", "NOT_CONNECTED")
                .put("cloud_10_percent_short_and_weekly", "FAIL_CLOSED")
                .put("player_strategy_modified_by_report", false)
                .put("spectator_gestures_allowed", false))
            .put("quality_kpis", JSONObject()
                .put("frame_inspections", inspections)
                .put("complete_single_frame_candidates", observations.optLong("single_frame_candidates"))
                .put("two_frame_agreements", observations.optLong("two_frame_agreements"))
                .put("ambiguous", observations.optLong("uncertain"))
                .put("no_detected_board", observations.optLong("no_visible_board"))
                .put("candidate_changes_not_errors", observations.optLong("candidate_changes"))
                .put("human_reviewed_recognized", reviewed)
                .put("human_confirmed", confirmed)
                .put("human_corrected", corrected)
                .put("human_labeled_unrecognized", observations.optLong("operator_labeled_unrecognized"))
                .put("complete_candidate_percent_not_accuracy",
                    if (inspections > 0) complete * 100 / inspections else JSONObject.NULL)
                .put("reviewed_agreement_percent_not_accuracy",
                    if (reviewed > 0) confirmed * 100 / reviewed else JSONObject.NULL)
                .put("readiness", readiness))
            .put("performance", JSONObject()
                .put("local_observation_mean_ms", if (avgMs >= 0) avgMs else JSONObject.NULL)
                .put("max_ms", perf.optLong("processing_ms_max"))
                .put("bins", perf))
            .put("board_timeline", observations.optJSONObject("timeline") ?: JSONObject())
            .put("spectator_numeric_ocr", observations.optJSONObject("numeric_ocr") ?: JSONObject())
            .put("global_incidents", incidentSummary(incidents))
            .put("prioritized_issues", issues)
            .put("spectator_examples", observations.optJSONArray("examples") ?: JSONArray())
            .put("validation_plan", JSONArray()
                .put("Preserve signed code79 baseline; don't change player inspectAll or strategy")
                .put("Annotate public board on independently reviewed screenshots; never infer correctness from agreement")
                .put("Replay fixed labeled captures against current and shadow readers")
                .put("Check STOP, package/epoch invalidation, spectator gesture prohibition")
                .put("Run Python, JVM, Android unit, signing/upgrade, and Android16 device/instrumentation checks")
                .put("Promote separately versioned observer reader only after measurable regression improvement"))
            .put("delivery", JSONObject()
                .put("export", "MANUAL_ANDROID_DOCUMENT_PICKER")
                .put("automatic_chat_upload", "NOT_IMPLEMENTED")
                .put("automatic_github_issue", "NOT_IMPLEMENTED")
                .put("report_retention", "BOUNDED_LOCAL")
                .put("screenshots", "NOT_STORED"))
        return report
    }

    @Synchronized fun export(context: Context): String {
        val observations = JSONObject(SpectatorLearningJournal.exportJson(context))
        val incidents = JSONObject(OnDeviceIncidentRecorder.exportReport(context))
        @Suppress("DEPRECATION")
        val version = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (_: Exception) { "unknown" }
        val report = build(observations, incidents, version, Build.VERSION.SDK_INT,
            System.currentTimeMillis())
        report.put("device_health", SpectatorDeviceHealth.snapshot(context))
        return report.toString(2)
    }

    fun brief(context: Context): String = try {
        val root = JSONObject(export(context))
        val kpis = root.getJSONObject("quality_kpis")
        val issues = root.getJSONArray("prioritized_issues")
        val p1 = (0 until issues.length()).count {
            issues.optJSONObject(it)?.optString("priority") == "P1"
        }
        "T042 ИНЖЕНЕРНЫЙ ОТЧЁТ\\n" +
            "Проверок: ${kpis.optLong("frame_inspections")}; согласований: " +
            "${kpis.optLong("two_frame_agreements")}; ошибок / сомнений: " +
            "${kpis.optLong("ambiguous")}.\\n" +
            "Подтверждено вручную: ${kpis.optLong("human_confirmed")}; " +
            "исправлено: ${kpis.optLong("human_corrected")}.\\n" +
            "Задач P1: $p1, всего рекомендаций: ${issues.length()}.\\n" +
            "Готовность данных: ${kpis.optString("readiness")}. " +
            "Экспортируйте полный JSON для гипотез, доказательств и тест-плана."
    } catch (_: Exception) { "Инженерный отчёт пока недоступен. Исходные журналы сохранены." }
}
