package com.example.airemote

import android.content.Context
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Bounded, local-only spectator dataset. Never used by the PLAYER reader,
 * game decision path, cloud, hand learner or gesture controls.
 *
 * Repeated frames can establish CONSISTENCY, not the ground-truth identity of
 * a card. Training labels exist ONLY after explicit operator feedback.
 * Does not persist images, OCR text, packages, names, credentials or tokens.
 */
object SpectatorLearningJournal {
    private const val PREFS = "spectator_observations_v1"
    private const val KEY = "bounded_events_and_counts"
    private const val LIMIT = 80
    private const val MIN_UNCERTAIN_EVENT_GAP_MS = 15_000L
    private const val MIN_STABLE_EVENT_GAP_MS = 25_000L
    private val boardTimeline = SpectatorBoardTimeline()

    @Volatile private var storageFailure = false
    @Volatile private var lastLoggedKind = ""
    @Volatile private var lastLoggedAt = 0L
    @Volatile private var lastStableKey = ""
    @Volatile private var lastStableAt = 0L

    private fun read(context: Context): JSONObject {
        val s = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null)
        return if (s.isNullOrBlank()) JSONObject() else JSONObject(s)
    }

    private fun save(context: Context, root: JSONObject) {
        val encoded = root.toString()
        require(encoded.length <= 28_000) { "Spectator journal size limit" }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, encoded).apply()
    }

    private fun count(root: JSONObject, key: String) {
        root.put(key, root.optLong(key, 0L) + 1L)
    }

    private fun append(root: JSONObject, type: String, observedAtMs: Long,
                       frameSeq: Long, proposedCards: List<String?>, correctedCards: List<String>? = null) {
        val old = root.optJSONArray("examples") ?: JSONArray()
        val sample = JSONObject()
            .put("type", type)
            .put("timestamp_ms", observedAtMs)
            .put("frame_sequence", frameSeq)
            .put("candidate", JSONArray(proposedCards))
            .put("operator_label", if (correctedCards == null) JSONObject.NULL else JSONArray(correctedCards))
            .put("is_ground_truth", type == "OPERATOR_CONFIRMED" ||
                type == "OPERATOR_CORRECTED" || type == "OPERATOR_LABELED_UNRECOGNIZED")
        val entries = JSONArray()
        val first = (old.length() - (LIMIT - 1)).coerceAtLeast(0)
        for (i in first until old.length()) entries.put(old.getJSONObject(i))
        entries.put(sample)
        root.put("examples", entries)
    }

    /**
     * Called only AFTER successful spectator-only atomic frame publication.
     * A legitimate flop->turn, turn->river or new hand may change the board:
     * count it as a difference, not a proven OCR error.
     */
    @Synchronized fun observe(context: Context, reading: VisibleCommunityReading,
                              stable: StableSpectatorBoard?, capturedAtMs: Long,
                              frameSeq: Long) {
        if (!CaptureObservationMode.isSpectator()) return
        try {
            val root = read(context)
            count(root, "inspections")
            val processingMs = SystemClock.elapsedRealtime() - capturedAtMs
            if (processingMs in 0L..15_000L) {
                count(root, "latency_samples")
                root.put("processing_ms_sum", root.optLong("processing_ms_sum") + processingMs)
                root.put("processing_ms_max", maxOf(root.optLong("processing_ms_max"), processingMs))
                count(root, when {
                    processingMs <= 100 -> "latency_0_100"
                    processingMs <= 300 -> "latency_101_300"
                    processingMs <= 700 -> "latency_301_700"
                    processingMs <= 1500 -> "latency_701_1500"
                    else -> "latency_over_1500"
                })
            }
            val observedAtMs = System.currentTimeMillis()
            val token = reading.cards.joinToString(",") { it ?: "?" }
            val waitingForFlop = reading.cards.isEmpty() &&
                reading.reason.contains("ещё не появились")
            val category = when {
                waitingForFlop -> "WAITING_OR_UNDETECTED"
                !reading.complete -> "UNCERTAIN"
                stable != null -> "TWO_FRAME_AGREEMENT"
                else -> "SINGLE_FRAME_CANDIDATE"
            }
            count(root, when (category) {
                "WAITING_OR_UNDETECTED" -> "no_visible_board"
                "UNCERTAIN" -> "uncertain"
                "TWO_FRAME_AGREEMENT" -> "two_frame_agreements"
                else -> "single_frame_candidates"
            })
            // Do not claim this change is an error: new deals, added cards,
            // and wrong detections all look similar without human labels.
            val previous = root.optString("last_visible_candidate", "")
            if (reading.complete && previous.isNotEmpty() && previous != token) {
                count(root, "candidate_changes")
            }
            if (reading.complete) root.put("last_visible_candidate", token)
            if (category == "UNCERTAIN" &&
                (lastLoggedKind != category ||
                    capturedAtMs >= lastLoggedAt + MIN_UNCERTAIN_EVENT_GAP_MS)) {
                append(root, category, observedAtMs, frameSeq, reading.cards)
                lastLoggedKind = category
                lastLoggedAt = capturedAtMs
            }
            if (stable != null) {
                val event = boardTimeline.observe(stable)
                if (event != SpectatorBoardEvent.STABLE_UNCHANGED) {
                    count(root, "timeline_" + event.name.lowercase())
                    append(root, "BOARD_EVENT_" + event.name, observedAtMs,
                        frameSeq, stable.cards)
                }
                val key = stable.cards.joinToString(",")
                if (key != lastStableKey ||
                    capturedAtMs >= lastStableAt + MIN_STABLE_EVENT_GAP_MS) {
                    append(root, "TWO_FRAME_AGREEMENT", observedAtMs, frameSeq,
                        stable.cards)
                    lastStableKey = key
                    lastStableAt = capturedAtMs
                }
            }
            save(context, root)
            storageFailure = false
        } catch (_: Exception) {
            storageFailure = true
        }
    }

    /** Numeric OCR: unverified public pot and CALL label candidates only.
     *  Never interpret the CALL label as an opponent action. */
    @Synchronized fun observeNumericHint(context: Context, hint: SpectatorNumericHint) {
        if (!CaptureObservationMode.isSpectator() ||
            hint.captureEpoch != FrameStore.captureEpoch) return
        try {
            val root = read(context)
            count(root, "numeric_ocr_samples")
            if (hint.potCandidateChips != null) {
                count(root, "pot_candidate_samples")
                val previous = root.optLong("last_pot_candidate", -1L)
                if (previous >= 0L && previous != hint.potCandidateChips)
                    count(root, "pot_changes_not_confirmed_bets")
                root.put("last_pot_candidate", hint.potCandidateChips)
            }
            if (hint.callLabelCandidateChips != null) count(root, "call_label_samples")
            if (hint.rejectedLabels > 0) {
                count(root, "numeric_ocr_uncertain_samples")
                root.put("numeric_ocr_rejected_total",
                    root.optLong("numeric_ocr_rejected_total") + hint.rejectedLabels)
            }
            save(context, root)
        } catch (_: Exception) { storageFailure = true }
    }

    /** Text anchors around the central row, without saving raw OCR. */
    @Synchronized fun observeBoardAnchor(context: Context,
                                         anchor: CenterTableAnchorEvidence) {
        if (!CaptureObservationMode.isSpectator() || !FrameStore.isCapturing ||
            OperatorStopStore.isStopped(context)) return
        try {
            val root = read(context)
            count(root, "board_anchor_ocr_frames")
            if (anchor.bankLabelAbove) count(root, "bank_label_above_board")
            if (anchor.chipAmountBelow) count(root, "chip_amount_below_board")
            if (anchor.bankLabelAbove && anchor.chipAmountBelow)
                count(root, "both_centered_board_anchors")
            save(context, root)
        } catch (_: Exception) { storageFailure = true }
    }

    /** Explicit ground-truth annotation, never automatic self-training. */
    @Synchronized fun operatorFeedback(context: Context, candidate: StableSpectatorBoard,
                                       correctCards: List<String>): Boolean {
        if (!CaptureObservationMode.isSpectator() ||
            !SpectatorBoardLabels.isValid(correctCards) ||
            correctCards.size != candidate.cards.size) return false
        return try {
            val root = read(context)
            val matching = candidate.cards == correctCards
            count(root, if (matching) "operator_confirmed" else "operator_corrected")
            append(root, if (matching) "OPERATOR_CONFIRMED" else "OPERATOR_CORRECTED",
                System.currentTimeMillis(), candidate.frameSequence,
                candidate.cards, correctCards)
            save(context, root)
            storageFailure = false
            true
        } catch (_: Exception) {
            storageFailure = true
            false
        }
    }

    /**
     * An observer may manually label the visible board even when OCR/card
     * recognition failed. This is a user-provided annotation, not inferred
     * from the image; the original screen image is never stored.
     */
    @Synchronized fun operatorLabelUnrecognized(context: Context, frame: FrameInfo,
                                                correctCards: List<String>): Boolean {
        if (!CaptureObservationMode.isSpectator() ||
            !FrameStore.isCapturing || FrameStore.captureEpoch != frame.captureEpoch ||
            !SpectatorBoardLabels.isValid(correctCards)) return false
        return try {
            val root = read(context)
            count(root, "operator_labeled_unrecognized")
            append(root, "OPERATOR_LABELED_UNRECOGNIZED", System.currentTimeMillis(),
                frame.seq, emptyList(), correctCards)
            save(context, root)
            storageFailure = false
            true
        } catch (_: Exception) {
            storageFailure = true
            false
        }
    }

    @Synchronized fun summary(context: Context): String = try {
        val root = read(context)
        val records = root.optJSONArray("examples")?.length() ?: 0
        "НАБЛЮДЕНИЕ — локальный журнал:\n" +
            "Проверок кадров: ${root.optLong("inspections")}; " +
            "без определённых общих карт: ${root.optLong("no_visible_board")}; " +
            "сомнительные: ${root.optLong("uncertain")}; " +
            "один кадр: ${root.optLong("single_frame_candidates")}; " +
            "согласование 2 кадров: ${root.optLong("two_frame_agreements")}; " +
            "смена предполагаемых карт: ${root.optLong("candidate_changes")}.\n" +
            "Оператор подтвердил: ${root.optLong("operator_confirmed")}; " +
            "исправил: ${root.optLong("operator_corrected")}; " +
            "разметил неопознанные: ${root.optLong("operator_labeled_unrecognized")}; " +
            "сохранено последних примеров: $records из $LIMIT.\n" +
            "Средняя задержка обработки: " +
            (if (root.optLong("latency_samples") > 0)
                "${root.optLong("processing_ms_sum") / root.optLong("latency_samples")} мс"
             else "не измерена") + ". " +
            "Переходы флоп→тёрн: ${root.optLong("timeline_possible_flop_to_turn")}, " +
            "тёрн→ривер: ${root.optLong("timeline_possible_turn_to_river")}.\\n" +
            "Облачные запросы: отключены; OCR банка: " +
            "${root.optLong("pot_candidate_samples")} возможных значений.\\n" +
            "Совпадение двух кадров НЕ является доказательством правильной карты. " +
            "Алгоритм и стратегия автоматически НЕ изменяются." +
            (if (storageFailure) " Ошибка последней записи журнала." else "")
    } catch (_: Exception) { "Не удалось прочитать журнал наблюдателя" }

    @Synchronized fun exportJson(context: Context): String {
        val root = read(context)
        val out = JSONObject()
            .put("schema", "t042-spectator-training-examples-v1")
            .put("exported_at_ms", System.currentTimeMillis())
            .put("description", "read-only spectator candidate observations; manual labels only are ground truth")
            .put("inspections", root.optLong("inspections"))
            .put("no_visible_board", root.optLong("no_visible_board"))
            .put("uncertain", root.optLong("uncertain"))
            .put("single_frame_candidates", root.optLong("single_frame_candidates"))
            .put("two_frame_agreements", root.optLong("two_frame_agreements"))
            .put("candidate_changes", root.optLong("candidate_changes"))
            .put("operator_confirmed", root.optLong("operator_confirmed"))
            .put("operator_corrected", root.optLong("operator_corrected"))
            .put("operator_labeled_unrecognized", root.optLong("operator_labeled_unrecognized"))
            .put("board_anchor_ocr", JSONObject()
                .put("processed_frames", root.optLong("board_anchor_ocr_frames"))
                .put("bank_label_above", root.optLong("bank_label_above_board"))
                .put("chip_amount_below", root.optLong("chip_amount_below_board"))
                .put("both_anchors_on_frame", root.optLong("both_centered_board_anchors")))
            .put("numeric_ocr", JSONObject()
                .put("processed_frames", root.optLong("numeric_ocr_samples"))
                .put("pot_candidates", root.optLong("pot_candidate_samples"))
                .put("call_label_candidates", root.optLong("call_label_samples"))
                .put("ambiguous", root.optLong("numeric_ocr_uncertain_samples"))
                .put("rejected_total", root.optLong("numeric_ocr_rejected_total"))
                .put("pot_changes_not_proven_bets",
                    root.optLong("pot_changes_not_confirmed_bets")))
            .put("timeline", JSONObject()
                .put("first_visible_flop", root.optLong("timeline_first_visible_flop"))
                .put("first_visible_turn", root.optLong("timeline_first_visible_turn"))
                .put("first_visible_river", root.optLong("timeline_first_visible_river"))
                .put("possible_flop_to_turn", root.optLong("timeline_possible_flop_to_turn"))
                .put("possible_turn_to_river", root.optLong("timeline_possible_turn_to_river"))
                .put("possible_skipped_street", root.optLong("timeline_possible_skipped_street"))
                .put("possible_new_hand", root.optLong("timeline_possible_new_hand"))
                .put("board_replaced_unverified", root.optLong("timeline_board_replaced_unverified")))
            .put("performance", JSONObject()
                .put("sample_count", root.optLong("latency_samples"))
                .put("processing_ms_sum", root.optLong("processing_ms_sum"))
                .put("processing_ms_max", root.optLong("processing_ms_max"))
                .put("latency_0_100", root.optLong("latency_0_100"))
                .put("latency_101_300", root.optLong("latency_101_300"))
                .put("latency_301_700", root.optLong("latency_301_700"))
                .put("latency_701_1500", root.optLong("latency_701_1500"))
                .put("latency_over_1500", root.optLong("latency_over_1500")))
            .put("examples", root.optJSONArray("examples") ?: JSONArray())
        return out.toString(2)
    }
}

/** Strict manually entered OPEN-board labels: "Ah Td 3c" (3/4/5 distinct). */
object SpectatorBoardLabels {
    private val card = Regex("^[2-9TJQKA][cdhs]$")
    fun isValid(cards: List<String>): Boolean =
        cards.size in 3..5 && cards.toSet().size == cards.size &&
            cards.all { card.matches(it) }
    fun parse(value: String): List<String>? {
        if (value.length > 64) return null
        val words = value.trim().split(Regex("[,;\\s]+")).filter { it.isNotBlank() }
        val cards = words.map { word ->
            if (word.length !in 2..3) return null
            val n = word.uppercase()
            val suit = n.last().lowercaseChar()
            val rank = n.dropLast(1).let { if (it == "10") "T" else it }
            "$rank$suit"
        }
        return cards.takeIf(::isValid)
    }
}
