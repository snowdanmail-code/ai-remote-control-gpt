package com.example.airemote

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

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
            .put("is_ground_truth", type == "OPERATOR_CONFIRMED" || type == "OPERATOR_CORRECTED")
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
            "сохранено последних примеров: $records из $LIMIT.\n" +
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
