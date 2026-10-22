package com.example.airemote

import android.content.Context
import org.json.JSONObject
import org.json.JSONArray

/**
 * Strictly read-only live PLAYER vision diagnostics. Records bounded aggregate
 * health, not images, player names, chip stakes, opponent data or OAuth text.
 * It cannot mint game/table/hand/turn identity or authorize gestures.
 */
object PlayerVisionJournal {
    private const val PREFS = "t042_player_vision_diagnostics_v1"
    private const val KEY = "stats"
    private const val MAX_JSON_BYTES = 6_000

    private fun read(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(KEY, null)?.let { JSONObject(it) } ?: JSONObject()

    @Synchronized fun observe(context: Context, reading: CardReading,
                              stable: StableCardReading?, epoch: Long, seq: Long,
                              sourceWidth: Int = -1, sourceHeight: Int = -1) {
        if (CaptureObservationMode.isSpectator() ||
            !FrameStore.isCapturing || FrameStore.captureEpoch != epoch ||
            OperatorStopStore.isStopped(context)) return
        try {
            val root = read(context)
            fun inc(key: String) { root.put(key, root.optLong(key, 0L) + 1L) }
            inc("frames_inspected")
            inc("card_failure_" + PlayerVisualIssueDiagnosis.cardFailure(reading).name)
            if (sourceWidth in 1..10000 && sourceHeight in 1..20000) {
                root.put("last_source_width_px", sourceWidth)
                root.put("last_source_height_px", sourceHeight)
            }
            if (reading.complete) inc("single_frame_candidates")
            else inc("incomplete")
            if (reading.hole.size != 2 || reading.hole.any { it == null })
                inc("hole_cards_missing_or_partial")
            if (reading.community.any { it == null })
                inc("community_cards_partial")
            if (stable != null && stable.captureEpoch == epoch &&
                stable.frameSequence == seq) inc("two_frame_agreements")
            // Never infer an actual hand boundary from an apparent street count.
            val publicCount = reading.community.count { it != null }
            if (publicCount in listOf(0, 3, 4, 5)) {
                inc("visible_board_count_$publicCount")
            } else inc("board_count_unverified")
            root.put("last_seen_capture_epoch", epoch)
            root.put("last_seen_vision_sequence", seq)
            val out = root.toString()
            if (out.length <= MAX_JSON_BYTES)
                context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY, out).apply()
        } catch (_: Exception) { /* Never fail capture for a diagnostic */ }
    }

    /** Candidate-only OCR aggregates. Never record raw text, button bounds or
     *  personally identifying labels; they cannot authorize a game move. */
    @Synchronized fun observeOcr(context: Context, result: FrameTextInterpretation,
                                 expectedEpoch: Long,
                                 positionedLines: List<PositionedOcrLine> = emptyList()) {
        if (CaptureObservationMode.isSpectator() || !FrameStore.isCapturing ||
            FrameStore.captureEpoch != expectedEpoch ||
            OperatorStopStore.isStopped(context)) return
        try {
            val root = read(context)
            fun inc(key: String) { root.put(key, root.optLong(key, 0L) + 1L) }
            inc("ocr_frames")
            val geometry = PlayerOcrGeometryHints.inspect(positionedLines, result)
            if (geometry.hasAnyText) inc("ocr_has_any_text")
            if (geometry.actionWordAnywhere) inc("ocr_action_word_anywhere")
            if (geometry.actionWordExpectedStrip) inc("ocr_action_word_expected_strip")
            if (geometry.actionWordOutsideStrip) inc("ocr_action_word_outside_strip")
            if (geometry.actionWordDecoratedOrRejected) inc("ocr_action_word_rejected_by_strict_parser")
            if (geometry.potWordAnywhere) inc("ocr_pot_word_anywhere")
            if (geometry.callWordAnywhere) inc("ocr_call_word_anywhere")
            if (result.actions.isEmpty()) inc("ocr_no_action_labels")
            else inc("ocr_action_labels_seen")
            if (result.numbers.any { it.kind == NumericCandidateKind.POT })
                inc("ocr_pot_label_candidates")
            if (result.numbers.any { it.kind == NumericCandidateKind.CALL_AMOUNT })
                inc("ocr_call_label_candidates")
            if (result.rejected.isNotEmpty()) inc("ocr_ambiguous_frames")
            val output = root.toString()
            if (output.length <= MAX_JSON_BYTES)
                context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY, output).apply()
        } catch (_: Exception) { /* diagnostics only */ }
    }

    @Synchronized fun snapshot(context: Context): JSONObject = try {
        val root = read(context)
        JSONObject()
            .put("frames_inspected", root.optLong("frames_inspected"))
            .put("single_frame_candidates", root.optLong("single_frame_candidates"))
            .put("incomplete", root.optLong("incomplete"))
            .put("hole_cards_missing_or_partial", root.optLong("hole_cards_missing_or_partial"))
            .put("community_cards_partial", root.optLong("community_cards_partial"))
            .put("two_frame_agreements", root.optLong("two_frame_agreements"))
            .put("ocr_frames", root.optLong("ocr_frames"))
            .put("ocr_action_labels_seen", root.optLong("ocr_action_labels_seen"))
            .put("ocr_no_action_labels", root.optLong("ocr_no_action_labels"))
            .put("ocr_pot_label_candidates", root.optLong("ocr_pot_label_candidates"))
            .put("ocr_call_label_candidates", root.optLong("ocr_call_label_candidates"))
            .put("ocr_ambiguous_frames", root.optLong("ocr_ambiguous_frames"))
            .put("visual_failure_breakdown", JSONObject()
                .put("unsupported_screen_layout", root.optLong("card_failure_UNSUPPORTED_LAYOUT"))
                .put("hole_region_missing", root.optLong("card_failure_HOLE_REGION_MISSING"))
                .put("hole_rank_or_suit_unreadable", root.optLong("card_failure_HOLE_GLYPHS_UNREADABLE"))
                .put("community_rank_or_suit_unreadable", root.optLong("card_failure_COMMUNITY_GLYPHS_UNREADABLE"))
                .put("duplicate_or_invalid_cards", root.optLong("card_failure_INVALID_OR_DUPLICATE_CARDS"))
                .put("other_incomplete", root.optLong("card_failure_OTHER_INCOMPLETE"))
                .put("complete_candidates", root.optLong("card_failure_COMPLETE_CANDIDATE")))
            .put("ocr_geometry_hints_not_authority", JSONObject()
                .put("frames_with_any_text", root.optLong("ocr_has_any_text"))
                .put("frames_with_action_word_anywhere", root.optLong("ocr_action_word_anywhere"))
                .put("frames_with_action_word_in_expected_strip", root.optLong("ocr_action_word_expected_strip"))
                .put("frames_with_action_word_outside_strip", root.optLong("ocr_action_word_outside_strip"))
                .put("frames_with_action_word_rejected_by_strict_parser", root.optLong("ocr_action_word_rejected_by_strict_parser"))
                .put("frames_with_pot_word_anywhere", root.optLong("ocr_pot_word_anywhere"))
                .put("frames_with_call_word_anywhere", root.optLong("ocr_call_word_anywhere")))
            .put("last_source_dimensions", JSONObject()
                .put("width_px", root.optInt("last_source_width_px", -1))
                .put("height_px", root.optInt("last_source_height_px", -1)))
            .put("visible_board_counts_are_only_hints", JSONObject()
                .put("preflop_zero", root.optLong("visible_board_count_0"))
                .put("flop_three", root.optLong("visible_board_count_3"))
                .put("turn_four", root.optLong("visible_board_count_4"))
                .put("river_five", root.optLong("visible_board_count_5"))
                .put("unverified", root.optLong("board_count_unverified")))
    } catch (_: Exception) { JSONObject().put("diagnostics_unavailable", true) }

    @Synchronized fun summary(context: Context): String {
        val data = snapshot(context)
        return "ИИ-ИГРОК — локальный анализ кадров:\n" +
            "Проверено: ${data.optLong("frames_inspected")}; " +
            "полный кандидат: ${data.optLong("single_frame_candidates")}; " +
            "неполные: ${data.optLong("incomplete")}; " +
            "совпадение 2 кадров: ${data.optLong("two_frame_agreements")}.\n" +
            "Собственные 2 карты отсутствуют/частичны: " +
            "${data.optLong("hole_cards_missing_or_partial")}.\n" +
            "Это распознавание, не проверка личности стола/хода и не разрешение касаний."
    }
}

/** Pure mathematical player-card diagnostic; NEVER equity or a bet decision. */
data class PlayerHandPreview(
    val hole: List<String>,
    val board: List<String>,
    val stage: HandStage,
    val bestMadeHand: PokerHandClass?
) {
    val actionAuthorized: Boolean get() = false
}

object PlayerHandEvaluator {
    fun inspect(stable: StableCardReading?): PlayerHandPreview? {
        if (stable == null || stable.hole.size != 2 ||
            stable.community.size !in listOf(0, 3, 4, 5)) return null
        val all = try {
            (stable.hole + stable.community).map(PokerCard::parse)
        } catch (_: IllegalArgumentException) { return null }
        if (all.toSet().size != all.size) return null
        val stage = when (stable.community.size) {
            0 -> HandStage.PREFLOP
            3 -> HandStage.FLOP
            4 -> HandStage.TURN
            else -> HandStage.RIVER
        }
        val hand = if (all.size >= 5) try {
            PokerMath.evaluate(all).category
        } catch (_: IllegalArgumentException) { return null } else null
        return PlayerHandPreview(stable.hole, stable.community, stage, hand)
    }
}
