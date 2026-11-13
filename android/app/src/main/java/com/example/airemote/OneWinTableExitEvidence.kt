package com.example.airemote

/**
 * Operator-opted-in, non-wager table exit. A missing OCR label is UNKNOWN,
 * never proof that a player has left. Every seat must have a calibrated
 * stack/empty label; the hero seat must be explicitly known.
 */
enum class OneWinSeatPresence { OCCUPIED, EMPTY }

data class OneWinExitSeatObservation(
    val packageName: String,
    val epoch: Long,
    val window: Long,
    val frameSequence: Long,
    val atMs: Long,
    val heroSeat: Int,
    val seats: Map<Int, OneWinSeatPresence>,
    val expectedSeats: Int
) {
    val occupied: Int get() = seats.values.count { it == OneWinSeatPresence.OCCUPIED }
    fun complete(): Boolean =
        packageName.isNotBlank() && epoch >= 0 && window >= 0 &&
        frameSequence > 0 && atMs >= 0 && heroSeat in 0..9 &&
        expectedSeats in 2..10 && seats.size == expectedSeats && heroSeat in seats &&
        seats[heroSeat] == OneWinSeatPresence.OCCUPIED &&
        seats.keys.all { it in 0..9 }
}

/** Require repeated exact occupancy, fresh source and VERIFIED non-playing stage. */
class OneWinTableExitEvidenceGate {
    private var initial: OneWinExitSeatObservation? = null
    private var previous: OneWinExitSeatObservation? = null
    private var matches = 0

    fun reset() { initial = null; previous = null; matches = 0 }

    @Synchronized fun inspect(
        sample: OneWinExitSeatObservation?,
        stage: HandStateSnapshot,
        nowMs: Long,
        stopped: Boolean,
        actionPending: Boolean
    ): Boolean {
        if (sample == null || !sample.complete() || stopped || actionPending ||
            !stage.trustworthyNow ||
            stage.stage !in setOf(HandStage.WAITING, HandStage.FINISHED) ||
            sample.occupied > 3 || sample.occupied < 1 ||
            nowMs < sample.atMs || nowMs - sample.atMs > 1_500L
        ) { reset(); return false }
        val p = previous
        if (p == null || p.packageName != sample.packageName ||
            p.epoch != sample.epoch || p.window != sample.window ||
            p.heroSeat != sample.heroSeat || p.seats != sample.seats ||
            sample.frameSequence <= p.frameSequence ||
            sample.atMs - p.atMs !in 900L..4_000L) {
            initial = sample
            previous = sample
            matches = 1
            return false
        }
        previous = sample
        matches++
        return matches >= 3 && sample.atMs - requireNotNull(initial).atMs >= 2_400L
    }
}

/**
 * Exact menu OCR, not inferred coordinates. The owner screenshot shows the
 * left drawer contains 'Покинуть игру', 'Касса', 'Настройки', 'Назад в лобби'.
 */
object OneWinTableExitMenuParser {
    private fun norm(s: String): String = s.trim().lowercase().replace(Regex("""\s+"""), " ")
    fun leaveButton(lines: List<PositionedOcrLine>): NormalizedTextBox? {
        val left = lines.filter { it.box.valid() && it.box.centerX() in 35..525 }
        val target = left.filter {
            norm(it.text) == "покинуть игру" &&
                it.box.centerY() in 95..280 &&
                it.box.right - it.box.left in 60..460
        }
        val support = left.any {
            norm(it.text) in setOf("касса", "настройки", "настройки стола", "назад в лобби")
        }
        return target.singleOrNull()?.box?.takeIf { support }
    }

    fun lobbyVisible(lines: List<PositionedOcrLine>): Boolean {
        val upper = lines.filter { it.box.valid() && it.box.centerY() in 25..215 }
        val header = upper.any {
            val t = norm(it.text)
            t == "кэш-игры" || t == "cash games"
        }
        val tableCount = upper.any {
            Regex("""^\d{1,3}\s*(?:столы|столов|tables)$""")
                .matches(norm(it.text))
        }
        return header && tableCount && leaveButton(lines) == null
    }
}
