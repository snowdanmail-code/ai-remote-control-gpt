package com.example.airemote

/**
 * Read-only observations measured from the owner's 1080x2340 1win cash-table
 * recordings (13 Nov 2026). This parser does not dispatch bets, raises or taps.
 *
 * Both videos use a six-seat portrait arrangement with HERO at screen bottom;
 * the UI may rotate actual seat numbers. Identify HERO by screen location,
 * never by nickname, chair number or Android package alone.
 */
enum class OneWinRecordedTablePanel {
    UNKNOWN, HERO_TURN, PREACTION, BET_SIZE_EDITOR, SHOWDOWN_CONTROLS
}

enum class OneWinRecordedAction { FOLD, CHECK, CALL, BET, RAISE }

data class OneWinRecordedActionLabel(
    val action: OneWinRecordedAction,
    val region: NormalizedTextBox,
    val amountCents: Long? = null
)

data class OneWinRecordedTableUiReading(
    val panel: OneWinRecordedTablePanel,
    val actions: List<OneWinRecordedActionLabel> = emptyList(),
    val proposedStakeCents: Long? = null,
    val evidence: String = ""
) {
    // Deliberately no gesture / transaction authority.
    val authorizesGesture: Boolean get() = false
}

object OneWinRecordedCashTableUi {
    // The right column of the original screenshot is the raise editor,
    // not a fourth seat or a second action strip.
    private val spaces = Regex("""\s+""")
    private val amount = Regex("""(?iu)(\d{1,5}(?:[.,]\d{1,2})?)\s*\$""")
    private fun clean(raw: String) = raw.lowercase().trim().replace(spaces, " ")

    fun moneyToCents(raw: String): Long? {
        if (!Regex("""\d{1,5}(?:[.,]\d{1,2})?""").matches(raw)) return null
        val split = raw.replace(',', '.').split('.')
        val whole = split[0].toLongOrNull() ?: return null
        val cents = when (split.getOrNull(1)?.length) {
            null -> 0L
            1 -> split[1].toLongOrNull()?.times(10L)
            2 -> split[1].toLongOrNull()
            else -> null
        } ?: return null
        return (whole * 100L + cents).takeIf { it in 1L..9_999_999L }
    }
    private fun amountAfter(label: String): Long? =
        amount.find(label)?.groupValues?.get(1)?.let(::moneyToCents)

    private fun lane(raw: String): Pair<OneWinRecordedAction, Long?>? {
        val s = clean(raw)
        return when {
            s in setOf("фолд", "fold", "пас") -> OneWinRecordedAction.FOLD to null
            s in setOf("чек", "check") -> OneWinRecordedAction.CHECK to null
            s in setOf("ставка", "bet") -> OneWinRecordedAction.BET to null
            s in setOf("рейз", "raise") -> OneWinRecordedAction.RAISE to null
            s == "колл" || s == "call" -> OneWinRecordedAction.CALL to null
            Regex("""(?iu)^(колл|call)\s*\d{1,5}(?:[.,]\d{1,2})?\s*\$$""")
                .matches(s) -> OneWinRecordedAction.CALL to amountAfter(s)
            else -> null
        }
    }

    private fun bottom(lines: List<PositionedOcrLine>) =
        lines.filter { it.box.valid() && it.box.centerY() in 902..995 &&
            it.box.centerX() in 20..990 &&
            it.text.length in 1..65 }

    private fun observedBetEditor(lines: List<PositionedOcrLine>): Long? {
        val controls = bottom(lines)
        fun has(word: String, from: Int, to: Int) = controls.any {
            clean(it.text) == word && it.box.centerX() in from..to
        }
        if (!has("мин", 30, 180) || !has("1/2", 150, 350) ||
            !has("банк", 320, 530) || !has("макс", 490, 690)) return null
        val confirm = controls.filter {
            clean(it.text).startsWith("подтвердить") &&
                it.box.centerX() in 665..990
        }.singleOrNull() ?: return null
        // Tesseract may place "Подтвердить" and "0.08 $" in separate lines.
        val inside = controls.filter {
            it.box.centerX() in 665..990 &&
                kotlin.math.abs(it.box.centerY() - confirm.box.centerY()) <= 48
        }
        val numbers = inside.mapNotNull { amountAfter(it.text) }.distinct()
        return numbers.singleOrNull()
    }

    fun read(lines: List<PositionedOcrLine>): OneWinRecordedTableUiReading {
        if (lines.size !in 3..150) return OneWinRecordedTableUiReading(
            OneWinRecordedTablePanel.UNKNOWN, evidence = "Нет достоверных строк OCR")
        val controls = bottom(lines)
        val editorAmount = observedBetEditor(lines)
        if (editorAmount != null) return OneWinRecordedTableUiReading(
            OneWinRecordedTablePanel.BET_SIZE_EDITOR,
            proposedStakeCents = editorAmount,
            evidence = "Мин / 1/2 / Банк / Макс / Подтвердить")
        // A partly recognized bet editor must NOT be mistaken for legal turn
        // actions (for example, its "Банк" and "Мин" are not bets).
        if (controls.any { clean(it.text).startsWith("подтвердить") ||
                clean(it.text) in setOf("мин", "1/2", "макс") })
            return OneWinRecordedTableUiReading(OneWinRecordedTablePanel.UNKNOWN,
                evidence = "Редактор ставки распознан частично")

        val byLane = listOf(0..325, 330..675, 680..995).map { band ->
            controls.mapNotNull { l ->
                if (l.box.centerX() !in band) null
                else lane(l.text)?.let { (action, value) ->
                    OneWinRecordedActionLabel(action, l.box, value)
                }
            }
        }
        val first = byLane[0].singleOrNull()
        val middle = byLane[1].singleOrNull()
        val right = byLane[2].singleOrNull()
        if (first?.action == OneWinRecordedAction.FOLD &&
            middle?.action in setOf(OneWinRecordedAction.CHECK, OneWinRecordedAction.CALL) &&
            right?.action in setOf(OneWinRecordedAction.BET, OneWinRecordedAction.RAISE))
            return OneWinRecordedTableUiReading(OneWinRecordedTablePanel.HERO_TURN,
                actions = listOf(first, middle!!, right!!),
                evidence = "Три отдельные нижние игровые кнопки")

        if (controls.count {
                val text = clean(it.text)
                text.contains("чек/фолд") || text.contains("уравнять все ставки") ||
                    text.contains("уравнять любую")
            } >= 1)
            return OneWinRecordedTableUiReading(OneWinRecordedTablePanel.PREACTION,
                evidence = "Предварительные действия, не текущий ход")

        if (controls.any {
                val label = clean(it.text)
                label.contains("открыть карты") ||
                    label.contains("скрыть карты при выигрыше")
            })
            return OneWinRecordedTableUiReading(OneWinRecordedTablePanel.SHOWDOWN_CONTROLS,
                evidence = "Кнопки вскрытия/скрытия карт, не игровой ход")

        return OneWinRecordedTableUiReading(OneWinRecordedTablePanel.UNKNOWN,
            evidence = "Нижняя панель не подтверждена")
    }
}

/**
 * Two independently sampled OCR frames may confirm only the VISIBLE UI mode.
 * Even a stable panel is not proof of hand identity, legal action or stake.
 */
class OneWinRecordedTableUiConsistency(private val maxGapMs: Long = 5_000L) {
    private var last: OcrFrameTicket? = null
    private var previous: OneWinRecordedTableUiReading? = null
    fun reset() { last = null; previous = null }

    @Synchronized fun observe(ticket: OcrFrameTicket,
                              reading: OneWinRecordedTableUiReading):
        OneWinRecordedTableUiReading {
        val p = last
        val q = previous
        val stable = p != null && q != null &&
            p.captureEpoch == ticket.captureEpoch &&
            p.windowGeneration == ticket.windowGeneration &&
            p.sourcePackage == ticket.sourcePackage &&
            ticket.frameSequence > p.frameSequence &&
            ticket.capturedAtMs > p.capturedAtMs &&
            ticket.capturedAtMs - p.capturedAtMs <= maxGapMs &&
            reading.panel != OneWinRecordedTablePanel.UNKNOWN &&
            reading.panel == q.panel &&
            reading.actions.map { it.action to it.amountCents } ==
                q.actions.map { it.action to it.amountCents } &&
            reading.proposedStakeCents == q.proposedStakeCents
        last = ticket
        previous = reading
        return if (stable) reading else OneWinRecordedTableUiReading(
            OneWinRecordedTablePanel.UNKNOWN,
            evidence = "Ожидаем второй согласованный кадр: " + reading.panel)
    }
}

/** Screen-relative HERO location, independent of actual numbered chair. */
object OneWinPortraitHeroSeat {
    fun inferBottomSeat(profile: RealFrameCalibration?): Int? {
        if (profile?.valid() != true) return null
        val seats = profile.zones.filter { it.role == RealFieldRole.SEAT_STACK &&
            it.seatIndex != null }.sortedByDescending { it.box.centerY() }
        val bottom = seats.firstOrNull() ?: return null
        val next = seats.getOrNull(1) ?: return null
        if (bottom.box.centerY() !in 745..925 ||
            bottom.box.centerY() - next.box.centerY() < 95) return null
        return bottom.seatIndex
    }
}
