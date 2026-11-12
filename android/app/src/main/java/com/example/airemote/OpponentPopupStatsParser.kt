package com.example.airemote

import kotlin.math.abs

/**
 * Read-only recognition of the statistics shown BELOW a tapped 1win avatar.
 * The real two-column popup puts each number ABOVE its short label:
 *
 * VPIP | PFR
 * ATS | 3-Бет
 * Контбет | Фолд на контбет
 * Чек-рейз | Руки
 *
 * OCR-only candidates are NOT a stable opponent identity, a poker fact, or
 * permission to touch any element of the game.
 */
enum class OpponentPopupMetric(val label: String, val percent: Boolean) {
    VPIP("VPIP", true),
    PFR("PFR", true),
    ATS("ATS", true),
    THREE_BET("3BET", true),
    C_BET("CBET", true),
    FOLD_TO_C_BET("FOLD_TO_CBET", true),
    CHECK_RAISE("CHECK_RAISE", true),
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
    private val labelPatterns: Map<OpponentPopupMetric, Regex> = mapOf(
        OpponentPopupMetric.VPIP to Regex("""(?i)vpip"""),
        OpponentPopupMetric.PFR to Regex("""(?i)pfr"""),
        OpponentPopupMetric.ATS to Regex("""(?i)ats"""),
        OpponentPopupMetric.THREE_BET to Regex("""(?i)3\s*[- ]?\s*(?:bet|бет)"""),
        OpponentPopupMetric.C_BET to Regex("""(?i)(?:c\s*[- ]?\s*bet|конт\s*[- ]?\s*бет)"""),
        OpponentPopupMetric.FOLD_TO_C_BET to Regex(
            """(?i)(?:(?:fold\s*(?:to|vs)\s*)|(?:фолд\s*(?:на|против)\s*))(?:c\s*[- ]?\s*bet|конт\s*[- ]?\s*бет)"""
        ),
        OpponentPopupMetric.CHECK_RAISE to Regex("""(?i)(?:check|чек)\s*[- ]?\s*(?:raise|рейз)"""),
        OpponentPopupMetric.WTSD to Regex("""(?i)wtsd"""),
        OpponentPopupMetric.WSD to Regex("""(?i)w\$?sd"""),
        OpponentPopupMetric.AGGRESSION_FACTOR to Regex("""(?i)(?:af|aggression\s+factor)"""),
        OpponentPopupMetric.HANDS to Regex("""(?i)(?:hands|руки|раздач[иа]?)""")
    )
    private val number = Regex("""[0-9]{1,6}(?:[.,][0-9]{1,2})?%?""")
    private val whitespace = Regex("""\s+""")

    private fun metricLabel(raw: String): OpponentPopupMetric? {
        val normalized = raw.trim().replace(whitespace, " ")
        return labelPatterns.entries.singleOrNull { it.value.matches(normalized) }?.key
    }

    private fun parseValue(raw: String, metric: OpponentPopupMetric): Double? {
        val clean = raw.trim().replace(whitespace, "")
        if (!number.matches(clean)) return null
        if (!metric.percent && clean.endsWith('%')) return null
        val value = clean.removeSuffix("%").replace(',', '.').toDoubleOrNull()
            ?.takeIf { it.isFinite() && it >= 0.0 } ?: return null
        return when {
            metric.percent && value <= 100.0 -> value
            metric == OpponentPopupMetric.AGGRESSION_FACTOR && value <= 100.0 -> value
            metric == OpponentPopupMetric.HANDS && value <= 100_000.0 && value % 1.0 == 0.0 -> value
            else -> null
        }
    }

    /** Strict labels, bounded numbers and no fuzzy guesses from opponent names. */
    fun parse(lines: List<PositionedOcrLine>): List<OpponentPopupMetricValue> {
        if (lines.size > 100) return emptyList()
        val valid = lines.filter { it.box.valid() && it.text.length in 1..64 }
        val candidates = mutableMapOf<OpponentPopupMetric, MutableList<Double>>()
        fun accept(metric: OpponentPopupMetric, raw: String) {
            parseValue(raw, metric)?.let { candidates.getOrPut(metric) { mutableListOf() }.add(it) }
        }

        // Compact OCR form: e.g. "43.4% VPIP" or "VPIP: 43.4%".
        for (line in valid) {
            val raw = line.text.trim().replace(whitespace, " ")
            for ((metric, label) in labelPatterns) {
                val m = label.find(raw) ?: continue
                if (m.range.first == 0) {
                    accept(metric, raw.substring(m.range.last + 1).trimStart(' ', ':', '='))
                } else if (m.range.last == raw.lastIndex) {
                    accept(metric, raw.substring(0, m.range.first).trimEnd(' ', ':', '='))
                }
            }
        }

        val labels = valid.mapNotNull { line ->
            metricLabel(line.text)?.let { it to line }
        }
        for ((metric, label) in labels) {
            // Some OCR engines put the number to the RIGHT of the label.
            val beside = valid.filter { n ->
                n !== label && n.box.left >= label.box.right &&
                    n.box.left - label.box.right <= 220 &&
                    abs(n.box.centerY() - label.box.centerY()) <= 14 &&
                    parseValue(n.text, metric) != null
            }
            // The supplied 1win screenshots put the number ABOVE the
            // corresponding label, in two parallel columns. Match based on
            // vertical order and X alignment, not on value colour.
            val above = valid.filter { n ->
                n !== label &&
                    n.box.centerY() < label.box.centerY() &&
                    label.box.top - n.box.bottom in 0..46 &&
                    abs(n.box.centerX() - label.box.centerX()) <= 75 &&
                    parseValue(n.text, metric) != null
            }
            val matched = (beside + above).distinct()
            if (matched.size == 1) accept(metric, matched.single().text)
        }

        // Conflicting duplicates are not resolved by picking an arbitrary OCR
        // value. A missing metric is NOT a measured zero.
        return candidates.mapNotNull { (metric, amounts) ->
            amounts.singleOrNull()?.let { OpponentPopupMetricValue(metric, it) }
        }.sortedBy { it.metric.ordinal }
    }
}
