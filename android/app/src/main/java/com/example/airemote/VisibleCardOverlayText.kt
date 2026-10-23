package com.example.airemote

/**
 * Pure display-only adapter. The owner should see what the recognizer read,
 * not cryptic Ah/Kd codes or a falsely authoritative move recommendation.
 *
 * Source is the same committed real capture and two-frame card consensus as
 * PLAYER diagnostics; no generated cards, cloud replies, game commands or taps.
 */
data class VisibleCardOverlaySnapshot(
    val hero: String,
    val board: String,
    val status: String,
    val isConfirmed: Boolean,
    val frameSequence: Long
)

object VisibleCardOverlayText {
    private val rank = mapOf(
        'A' to "Т", 'K' to "К", 'Q' to "Д",
        'J' to "В", 'T' to "10", '9' to "9",
        '8' to "8", '7' to "7", '6' to "6",
        '5' to "5", '4' to "4", '3' to "3", '2' to "2"
    )
    private val suits = mapOf(
        'h' to "♥", 's' to "♠",
        'd' to "♦", 'c' to "♣"
    )

    fun readable(code: String?): String {
        if (code == null || code.length != 2) return "НЕ РАСПОЗНАНО"
        val r = rank[code[0].uppercaseChar()] ?: return "НЕ РАСПОЗНАНО"
        val s = suits[code[1].lowercaseChar()] ?: return "НЕ РАСПОЗНАНО"
        return "$r $s"
    }

    fun show(cards: List<String?>, absent: String,
             glyphs: List<String> = emptyList()): String {
        val count = maxOf(cards.size, glyphs.size)
        if (count == 0) return absent
        return (0 until count).joinToString("  ·  ") { i ->
            val full = cards.getOrNull(i)
            if (full != null) readable(full)
            else glyphs.getOrNull(i)?.takeIf { it.isNotBlank() && it != "? ?" }
                ?: "НЕ РАСПОЗНАНО"
        }
    }

    /**
     * Preliminary values are shown explicitly as NOT confirmed; if a stable
     * two-frame read matches this SAME capture epoch, its status changes to
     * "СОВПАЛО НА 2 КАДРАХ". No past-hand preview is ever shown on top.
     */
    fun project(reading: CardReading?, stable: StableCardReading?,
                sampleEpoch: Long, sampleSequence: Long,
                sampleCapturedAtMs: Long, nowMs: Long,
                capturing: Boolean, stopped: Boolean,
                currentEpoch: Long, maxAgeMs: Long = 2_800L,
                bottomRankHint: PreflopRankHintSnapshot? = null,
                currentPreflopHint: PreflopRankHintSnapshot? = null): VisibleCardOverlaySnapshot? {
        if (!capturing || stopped || reading == null ||
            sampleEpoch < 0 || sampleEpoch != currentEpoch ||
            sampleSequence <= 0 ||
            nowMs < sampleCapturedAtMs ||
            nowMs - sampleCapturedAtMs > maxAgeMs) return null

        val same = stable != null &&
            stable.captureEpoch == sampleEpoch &&
            stable.frameSequence == sampleSequence &&
            nowMs >= stable.capturedAtMs &&
            nowMs - stable.capturedAtMs <= maxAgeMs &&
            reading.complete &&
            stable.hole == reading.hole &&
            stable.community == reading.community

        val bottomNow = bottomRankHint?.takeIf { hint ->
            currentPreflopHint?.allowed == true && hint.allowed &&
                hint.captureEpoch == sampleEpoch &&
                currentPreflopHint.captureEpoch == sampleEpoch &&
                hint.bottomTextRanks?.size == 2 &&
                hint.miniRanks.size == 2 &&
                reading.miniRankHints == hint.miniRanks &&
                currentPreflopHint.miniRanks == hint.miniRanks &&
                nowMs >= hint.observedAtMs &&
                nowMs - hint.observedAtMs <= maxAgeMs
        }?.bottomTextRanks
        val bottomLabel = bottomNow?.let { values ->
            "  |  ДО ФЛОПА: " + values.joinToString(" ") { c ->
                when (c) {
                    'A' -> "Т"; 'K' -> "К"; 'Q' -> "Д"; 'J' -> "В"
                    'T' -> "10"; else -> c.toString()
                }
            }
        }.orEmpty()

        return VisibleCardOverlaySnapshot(
            hero = show(reading.hole, "ИЩУ ДВЕ КАРТЫ", reading.holeGlyphs) +
                reading.miniRankHints.takeIf { it.size == 2 && it.any { rank -> rank != null } }
                    ?.let { "  |  МИНИ: " + it.joinToString(" ") { rank ->
                        when (rank) {
                            'A' -> "Т"; 'K' -> "К"; 'Q' -> "Д"; 'J' -> "В"
                            'T' -> "10"; null -> "?"; else -> rank.toString()
                        }
                    } }.orEmpty() + bottomLabel,
            board = show(reading.community, "ОБЩИЕ КАРТЫ НЕ НАЙДЕНЫ", reading.communityGlyphs),
            status = if (same) "СОВПАЛО НА 2 КАДРАХ" else "ПРЕДВАРИТЕЛЬНО · ПРОВЕРЬ",
            isConfirmed = same,
            frameSequence = sampleSequence
        )
    }
}
