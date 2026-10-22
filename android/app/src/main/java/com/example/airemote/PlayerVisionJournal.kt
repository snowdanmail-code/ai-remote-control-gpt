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
                              stable: StableCardReading?, epoch: Long, seq: Long) {
        if (CaptureObservationMode.isSpectator() ||
            !FrameStore.isCapturing || FrameStore.captureEpoch != epoch ||
            OperatorStopStore.isStopped(context)) return
        try {
            val root = read(context)
            fun inc(key: String) { root.put(key, root.optLong(key, 0L) + 1L) }
            inc("frames_inspected")
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

    @Synchronized fun snapshot(context: Context): JSONObject = try {
        val root = read(context)
        JSONObject()
            .put("frames_inspected", root.optLong("frames_inspected"))
            .put("single_frame_candidates", root.optLong("single_frame_candidates"))
            .put("incomplete", root.optLong("incomplete"))
            .put("hole_cards_missing_or_partial", root.optLong("hole_cards_missing_or_partial"))
            .put("community_cards_partial", root.optLong("community_cards_partial"))
            .put("two_frame_agreements", root.optLong("two_frame_agreements"))
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
