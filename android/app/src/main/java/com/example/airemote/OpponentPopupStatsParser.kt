package com.example.airemote

import kotlin.math.abs

/**
 * READ-ONLY text values in the popup opened from a 1win opponent avatar.
 *
 * No player identity, popup coordinate, click permission, or score is inferred
 * by this parser. These are OCR candidates, NOT verified opponent statistics.
 * A second agreeing panel capture is still required before display.
 */
enum class OpponentPopupMetric(val label: String, val percent: Boolean) {
    VPIP("VPIP", true),
    PFR("PFR", true),
    THREE_BET("3BET", true),
    C_BET("CBET", true),
    FOLD_TO_C_BET("FOLD_TO_CBET", true),
    WTSD("WTSD", true),
    WSD("WSD", true),
    AGGRESSION_FACTOR("AF", false),
    HANDS("HANDS", false)
}

data class OpponentPopupMetricValue(
    val metric: OpponentPopupMetric,
    val amount: Double,
    /** Always OCR-only: never a confirmed game-source field. */
    val evidence: String = "POPUP_OCR_ONLY"
)

object OpponentPopupStatsParser {
    private val fieldLabel: Map<OpponentPopupMetric, Regex> = mapOf(
        OpponentPopupMetric.VPIP to Regex("VPIP", RegexOption.IGNORE_CASE),
        OpponentPopupMetric.PFR to Regex("PFR", RegexOption.IGNORE_CASE),
        OpponentPopupMetric.THREE_BET to Regex("3\\s*[- ]?\\s*BET", RegexOption.IGNORE_CASE),
        OpponentPopupMetric.C_BET to Regex("C\\s*[- ]?\\s*BET", RegexOption.IGNORE_CASE),
        OpponentPopupMetric.FOLD_TO_C_BET to Regex("FOLD\\s+(?:TO\\s+)?C\\s*[- ]?\\s*BET", RegexOption.IGNORE_CASE),
        OpponentPopupMetric.WTSD to Regex("WTSD", RegexOption.IGNORE_CASE),
        OpponentPopupMetric.WSD to Regex("W\\$?SD", RegexOption.IGNORE_CASE),
        OpponentPopupMetric.AGGRESSION_FACTOR to Regex("(?:AF|AGGRESSION\\s+FACTOR)", RegexOption.IGNORE_CASE),
        OpponentPopupMetric.HANDS to Regex("(?:HANDS|РАЗДАЧ)", RegexOption.IGNORE_CASE)
    )
    private val number = Regex("(?:[0-9]{1,6})(?:[.,][0-9]{1,2})?%?")
    private val whitespace = Regex("\\s+")

    private fun metricLabel(text: String): OpponentPopupMetric? {
        val normalized = text.trim().replace(whitespace, " ")
        return fieldLabel.filterValues { it.matches(normalized) }.keys.singleOrNull()
    }

    private fun value(text: String, metric: OpponentPopupMetric): Double? {
        val compact = text.trim().replace(whitespace, "")
        if (!number.matches(compact)) return null
        val hasPercent = compact.endsWith("%")
        if (!metric.percent && hasPercent) return null
        val numeric = compact.removeSuffix("%").replace(',', '.').toDoubleOrNull()
            ?.takeIf { it.isFinite() && it >= 0 } ?: return null
        return when {
            metric.percent && numeric <= 100.0 -> numeric
            metric == OpponentPopupMetric.AGGRESSION_FACTOR && numeric <= 100.0 -> numeric
            metric == OpponentPopupMetric.HANDS && numeric <= 100_000.0 && numeric % 1.0 == 0.0 -> numeric
            else -> null
        }
    }

    /**
     * Uses ONLY literal OCR labels and adjacent numbers. Different popup
     * layouts may not match; unknown fields stay missing. Never fill zeros.
     */
    fun parse(lines: List<PositionedOcrLine>): List<OpponentPopupMetricValue> {
        if (lines.size > 100) return emptyList()
        val valid = lines.filter { it.box.valid() && it.text.length in 1..64 }
        val found = mutableMapOf<OpponentPopupMetric, MutableList<Double>>()
        fun record(metric: OpponentPopupMetric, raw: String) {
            value(raw, metric)?.let { found.getOrPut(metric) { mutableListOf() }.add(it) }
        }

        for (line in valid) {
            val raw = line.text.trim().replace(whitespace, " ")
            for ((metric, label) in fieldLabel) {
                val match = label.find(raw) ?: continue
                if (match.range.first != 0) continue
                // A label and one numeric value on the SAME line, nothing else.
                val suffix = raw.substring(match.range.last + 1).trimStart(' ', ':', '=')
                if (suffix.isNotBlank()) record(metric, suffix)
            }
        }
        // OCR sometimes returns the label and value as separate boxes.
        val labels = valid.mapNotNull { line ->
            metricLabel(line.text)?.let { it to line }
        }
        for ((metric, label) in labels) {
            val matches = valid.filter { line ->
                if (line === label) return@filter false
                val yDiff = abs(line.box.centerY() - label.box.centerY())
                line.box.left >= label.box.right &&
                    line.box.left - label.box.right <= 220 &&
                    yDiff <= 14 && value(line.text, metric) != null
            }
            if (matches.size == 1) record(metric, matches.single().text)
        }
        // Conflicting/duplicate candidates are never silently chosen.
        return found.mapNotNull { (metric, amounts) ->
            amounts.singleOrNull()?.let { OpponentPopupMetricValue(metric, it) }
        }.sortedBy { it.metric.ordinal }
    }
}
