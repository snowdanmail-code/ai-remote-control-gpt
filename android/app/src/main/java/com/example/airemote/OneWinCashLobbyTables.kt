package com.example.airemote

import kotlin.math.abs

/**
 * An on-device, read-only 1win CASH GAMES / Holdem lobby OCR model.
 * Rows and button anchors are derived only from visible labelled text, not
 * from hardcoded coordinates or imagined table profitability.
 *
 * Dollar amounts are represented in cents. A displayed "Бай-ин: 8$/20$"
 * is an ALLOWED RANGE, not an amount paid or an approved transaction.
 */
data class OneWinCashLobbyTable(
    val label: String,
    val occupiedSeats: Int,
    val totalSeats: Int,
    val smallBlindCents: Long,
    val bigBlindCents: Long,
    val minBuyInCents: Long,
    val maxBuyInCents: Long,
    val averagePotCents: Long?,
    val buyInOcrBox: NormalizedTextBox,
    val rowCenterY: Int
) {
    fun stableKey() = listOf(label.trim().lowercase(), occupiedSeats, totalSeats,
        smallBlindCents, bigBlindCents, minBuyInCents, maxBuyInCents,
        buyInOcrBox.centerX(), buyInOcrBox.centerY()).joinToString("|")
}

data class OneWinCashLobbyReading(
    val headerVerified: Boolean,
    val tables: List<OneWinCashLobbyTable>,
    val reason: String
) {
    val authorizesBuyIn: Boolean get() = false
    val authorizesWager: Boolean get() = false
}

object OneWinCashLobbyParser {
    private val ws = Regex("""\s+""")
    private val buyinLabel = Regex("""(?iu)(?:бай\s*[- ]?\s*ин|buy\s*[- ]?\s*in|войти)""")
    private val buyinRange = Regex("""(?iu)([0-9]{1,5}(?:[.,][0-9]{1,2})?)\s*\$\s*/\s*([0-9]{1,5}(?:[.,][0-9]{1,2})?)\s*\$?""")
    private val blindsRange = Regex("""(?iu)([0-9]{1,5}(?:[.,][0-9]{1,2})?)\s*\$\s*/\s*([0-9]{1,5}(?:[.,][0-9]{1,2})?)\s*\$""")
    private val players = Regex("""(?<!\d)([0-9]{1,2})\s*/\s*([0-9]{1,2})(?!\d)""")
    private val meanPot = Regex("""(?iu)(?:средний\s+банк|average\s+pot)\s*[:=]?\s*([0-9]{1,7}(?:[.,][0-9]{1,2})?)\s*\$""")
    private val tableLabel = Regex("""(?iu)[\p{L}\p{N}][\p{L}\p{N}\s#_.-]{2,44}""")

    private fun money(raw: String): Long? {
        if (!Regex("""[0-9]{1,7}(?:[.,][0-9]{1,2})?""").matches(raw)) return null
        val parts = raw.replace(',', '.').split('.')
        val whole = parts[0].toLongOrNull() ?: return null
        val fractional = when (parts.size) {
            1 -> 0L
            2 -> when (parts[1].length) {
                1 -> parts[1].toLongOrNull()?.times(10L)
                2 -> parts[1].toLongOrNull()
                else -> null
            }
            else -> null
        } ?: return null
        return (whole * 100L + fractional).takeIf { it in 1L..999_999_999L }
    }

    private fun amountPair(raw: String, regex: Regex): Pair<Long, Long>? {
        val m = regex.find(raw) ?: return null
        val first = money(m.groupValues[1]) ?: return null
        val last = money(m.groupValues[2]) ?: return null
        return (first to last).takeIf { it.first <= it.second }
    }

    /**
     * Strictly reject unrelated screenshots: the top bar must say CASH GAMES
     * and the view must show Holdem / a number of tables. Two-frame approval
     * and the Android package binding are separate observer requirements.
     */
    fun read(lines: List<PositionedOcrLine>): OneWinCashLobbyReading {
        if (lines.size !in 5..130) return OneWinCashLobbyReading(false, emptyList(), "NO_OCR")
        val valid = lines.filter { it.box.valid() && it.text.length in 1..70 }
        val upper = valid.filter { it.box.centerY() in 25..210 }
            .joinToString(" ") { it.text.lowercase().replace(ws, " ") }
        val isCashGames = (upper.contains("кэш") && upper.contains("игр")) ||
            upper.contains("cash games")
        val isHoldem = upper.contains("холдем") || upper.contains("holdem") ||
            upper.contains("столы") || upper.contains("tables")
        if (!isCashGames || !isHoldem)
            return OneWinCashLobbyReading(false, emptyList(), "NOT_CASH_GAMES_LOBBY")

        val anchors = valid.filter {
            it.box.centerX() in 690..970 &&
                it.box.centerY() in 180..940 &&
                buyinLabel.containsMatchIn(it.text)
        }.sortedBy { it.box.centerY() }
        val results = mutableListOf<OneWinCashLobbyTable>()
        for ((idx, anchor) in anchors.withIndex()) {
            val top = (anchor.box.centerY() - 18).coerceAtLeast(175)
            val bottom = minOf(anchor.box.centerY() + 85,
                anchors.getOrNull(idx + 1)?.box?.centerY()?.minus(8) ?: 950)
            val row = valid.filter { it.box.centerY() in top..bottom }
            if (row.size < 4) continue
            val right = row.filter { it.box.centerX() >= 690 }
            val rangeTokens = right.distinct()
                .mapNotNull { line -> amountPair(line.text, buyinRange)?.let { it to line } }
            val buyin = when {
                rangeTokens.size == 1 -> rangeTokens.single().first
                rangeTokens.isNotEmpty() -> null
                else -> {
                    // Split OCR: price is a separate line directly under 'Бай-ин'.
                    right.filter { it.box.centerY() in anchor.box.centerY()..(anchor.box.centerY() + 35) }
                        .mapNotNull { amountPair(it.text, buyinRange) }
                        .singleOrNull()
                }
            } ?: continue

            val blind = right.mapNotNull { l ->
                amountPair(l.text, blindsRange)?.takeIf { l.box.centerY() > anchor.box.centerY() + 18 }
            }.singleOrNull() ?: continue
            if (blind.first == 0L || blind.first >= blind.second) continue
            val seats = row.mapNotNull { l ->
                if (l.box.centerX() !in 440..725) null
                else players.find(l.text)?.let {
                    it.groupValues[1].toIntOrNull() to it.groupValues[2].toIntOrNull()
                }
            }.filter { it.first != null && it.second != null }
                .map { requireNotNull(it.first) to requireNotNull(it.second) }
                .singleOrNull() ?: continue
            if (seats.first !in 0..10 || seats.second !in 2..10 ||
                seats.first > seats.second) continue
            val average = row.mapNotNull { meanPot.find(it.text)?.groupValues?.get(1)?.let(::money) }
                .singleOrNull()

            // One named table on the left, above the average-pot label.
            val title = row.filter { line ->
                line.box.centerX() in 50..470 &&
                    line.box.centerY() in (anchor.box.centerY()+10)..(anchor.box.centerY()+70) &&
                    !line.text.contains("средний", ignoreCase = true) &&
                    !line.text.contains("холдем", ignoreCase = true) &&
                    !line.text.contains("holdem", ignoreCase = true) &&
                    tableLabel.matches(line.text.trim())
            }.minByOrNull { it.box.centerY() }?.text?.trim() ?: continue

            // The label itself lies INSIDE the blue BUY-IN table-row button.
            // It is an OCR anchor, never a freely editable betting target.
            if (anchor.box.centerY() !in 190..925) continue
            results.add(OneWinCashLobbyTable(title, seats.first, seats.second,
                blind.first, blind.second, buyin.first, buyin.second,
                average, anchor.box, anchor.box.centerY()))
        }
        return OneWinCashLobbyReading(true, results.distinctBy { it.stableKey() },
            if (results.isEmpty()) "LOBBY_BUT_UNVERIFIED_TABLE_ROWS" else "OCR_TABLE_CANDIDATES")
    }
}

/**
 * User-chosen financial exposure limits are REQUIRED even for an automated
 * lobby join. This selector never pays the buy-in or places a wager.
 */
data class OneWinCashTableLimits(
    val maxBigBlindCents: Long,
    val maxBuyInCents: Long,
    val minOpponents: Int = 2,
    val preferredOpponents: Int = 4
) {
    fun valid(): Boolean = maxBigBlindCents in 1..100_000 &&
        maxBuyInCents in 1..1_000_000 &&
        minOpponents in 1..9 && preferredOpponents in minOpponents..9
}

data class OneWinLobbyTableChoice(
    val table: OneWinCashLobbyTable,
    /** Suggested cap is NOT an approved transaction. */
    val permittedBuyInCapCents: Long
)

object OneWinCashLobbySelector {
    fun choose(tables: List<OneWinCashLobbyTable>,
               limits: OneWinCashTableLimits?): OneWinLobbyTableChoice? {
        if (limits?.valid() != true) return null
        val available = tables.filter { table ->
            table.bigBlindCents <= limits.maxBigBlindCents &&
                table.minBuyInCents <= limits.maxBuyInCents &&
                table.smallBlindCents > 0L &&
                table.bigBlindCents > table.smallBlindCents &&
                table.occupiedSeats >= limits.minOpponents &&
                table.occupiedSeats < table.totalSeats &&
                table.totalSeats <= 10 &&
                table.label.isNotBlank()
        }
        // Avoid claims that average pot size indicates profitability. Prefer
        // tables with sufficient opponents and lower blinds within limits.
        val chosen = available.sortedWith(
            compareBy<OneWinCashLobbyTable> {
                abs(it.occupiedSeats - limits.preferredOpponents)
            }.thenBy { it.bigBlindCents }
                .thenBy { it.minBuyInCents }
                .thenBy { it.label }
        ).firstOrNull() ?: return null
        return OneWinLobbyTableChoice(chosen,
            minOf(limits.maxBuyInCents, chosen.maxBuyInCents))
    }
}
