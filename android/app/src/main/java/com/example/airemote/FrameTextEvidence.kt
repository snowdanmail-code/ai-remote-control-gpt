package com.example.airemote

/** Normalized OCR rectangle: each coordinate is in 0..1000 (inclusive).
 * Input rectangles are raw *candidates*, not verified identities or permissions.
 */
data class NormalizedTextBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    fun valid(): Boolean = left in 0..1000 && top in 0..1000 && right in 0..1000 &&
        bottom in 0..1000 && left < right && top < bottom
    fun centerX(): Int = left + (right - left) / 2
    fun centerY(): Int = top + (bottom - top) / 2
}

data class PositionedOcrLine(val text: String, val box: NormalizedTextBox)

enum class ExactActionCandidate { FOLD, CHECK, CALL, BET, RAISE, CONFIRM }
enum class NumericCandidateKind { POT, CALL_AMOUNT, TIMER_SECONDS }

data class ActionLabelCandidate(
    val action: ExactActionCandidate,
    val box: NormalizedTextBox,
    val text: String
)
data class NumericLabelCandidate(
    val kind: NumericCandidateKind,
    val amount: Long,
    val box: NormalizedTextBox,
    val text: String
)

/** Diagnostic-only parse of ML Kit line rectangles.
 * No OCR label confirms source/app/table/hand/turn and this API cannot authorize tapping.
 * Ambiguous duplicate action labels or numerical values are discarded rather than guessed.
 */
data class FrameTextInterpretation(
    val actions: List<ActionLabelCandidate>,
    val numbers: List<NumericLabelCandidate>,
    val rejected: List<String>
)

object FrameTextEvidence {
    private val fullAction = listOf(
        ExactActionCandidate.FOLD to Regex("(?:fold|сброс|сбросить|пас)", RegexOption.IGNORE_CASE),
        ExactActionCandidate.CHECK to Regex("(?:check|чек|пропустить)", RegexOption.IGNORE_CASE),
        ExactActionCandidate.CALL to Regex("(?:call|колл|уравнять|ответить)", RegexOption.IGNORE_CASE),
        ExactActionCandidate.BET to Regex("(?:bet|ставка|поставить)", RegexOption.IGNORE_CASE),
        ExactActionCandidate.RAISE to Regex("(?:raise|рейз|повысить)", RegexOption.IGNORE_CASE),
        ExactActionCandidate.CONFIRM to Regex("(?:confirm|подтвердить|готово)", RegexOption.IGNORE_CASE)
    )
    // Integer-only chips. Decimal separators or currency formatting are deliberately not inferred.
    // Accept a single integer or groups separated by spaces, exactly 3 digits per group.
    private const val AMOUNT = "([0-9]{1,9}|[0-9]{1,3}(?:[ \u00a0\u202f][0-9]{3}){1,3})"
    private val potPattern = Regex("(?:pot|банк)\\s*[:=]?\\s*$AMOUNT", RegexOption.IGNORE_CASE)
    private val callPattern = Regex("(?:call|колл|уравнять|ответить)\\s*[:=]?\\s*$AMOUNT", RegexOption.IGNORE_CASE)
    private val timerPattern = Regex("(?:time|timer|время|таймер)\\s*[:=]?\\s*([0-9]{1,3})\\s*(?:s|sec|сек|с)?", RegexOption.IGNORE_CASE)

    private fun parseInteger(text: String): Long? {
        val compact = text.replace(" ", "").replace("\u00a0", "").replace("\u202f", "")
        return compact.toLongOrNull()?.takeIf { it in 0..999_999_999_999L }
    }

    fun parse(lines: List<PositionedOcrLine>): FrameTextInterpretation {
        val actions = mutableListOf<ActionLabelCandidate>()
        val numbers = mutableListOf<NumericLabelCandidate>()
        val rejected = mutableListOf<String>()
        for (line in lines.take(100)) {
            val raw = line.text.trim().replace(Regex("\\s+"), " ")
            if (raw.isEmpty()) continue
            if (!line.box.valid()) { rejected.add("Недостоверные координаты OCR"); continue }
            if (raw.length > 80) { rejected.add("Слишком длинная строка OCR"); continue }
            val centerX = line.box.centerX()
            val centerY = line.box.centerY()
            // Exact action labels only, limited to the calibrated bottom strip.
            // A label elsewhere (chat, history, statistics, player name) is NOT a control.
            if (centerY in 915..985) {
                val token = raw.lowercase().trim()
                val matching = fullAction.filter { (_, pattern) -> pattern.matches(token) }
                if (matching.size == 1) {
                    val role = matching.single().first
                    val correctLane = when (role) {
                        ExactActionCandidate.FOLD -> centerX in 0..333
                        ExactActionCandidate.CHECK, ExactActionCandidate.CALL -> centerX in 334..666
                        ExactActionCandidate.BET, ExactActionCandidate.RAISE, ExactActionCandidate.CONFIRM -> centerX in 667..1000
                    }
                    if (correctLane) actions.add(ActionLabelCandidate(role, line.box, raw))
                    else rejected.add("Название кнопки не в ожидаемой области")
                } else if (matching.size > 1) rejected.add("Несколько несовместимых значений кнопки")
            }
            val numeric = listOf(
                Triple(NumericCandidateKind.POT, potPattern, centerY in 100..910),
                Triple(NumericCandidateKind.CALL_AMOUNT, callPattern, centerY in 915..985 && centerX in 334..666),
                Triple(NumericCandidateKind.TIMER_SECONDS, timerPattern, centerY in 700..985)
            )
            for ((kind, pattern, inRegion) in numeric) {
                val match = pattern.matchEntire(raw) ?: continue
                if (!inRegion) { rejected.add("Число за пределами зоны ${kind.name}"); continue }
                val value = parseInteger(match.groupValues[1])
                if (value == null || (kind == NumericCandidateKind.TIMER_SECONDS && value > 120L)) {
                    rejected.add("Некорректный формат или значение ${kind.name}")
                } else numbers.add(NumericLabelCandidate(kind, value, line.box, raw))
            }
            // Partial/corrupted numeric labels do not get converted to zero or rounded.
            if (Regex("^(?:pot|банк|call|колл|time|timer|время|таймер)\\b", RegexOption.IGNORE_CASE)
                    .containsMatchIn(raw) && numeric.none { (_, p, _) -> p.matches(raw) }) {
                rejected.add("Неоднозначная подпись или число")
            }
        }
        // Multiple OCR hits for same role/kind must never count as one confirmed reading.
        val distinctActions = actions.groupBy { it.action }.filterValues { it.size == 1 }
            .values.map { it.single() }
        val distinctNumbers = numbers.groupBy { it.kind }.filterValues { it.size == 1 }
            .values.map { it.single() }
        if (distinctActions.size != actions.size) rejected.add("Дублирование подписи кнопки")
        if (distinctNumbers.size != numbers.size) rejected.add("Дублирование числового показания")
        if (distinctActions.any { it.action == ExactActionCandidate.CHECK } &&
            distinctActions.any { it.action == ExactActionCandidate.CALL }) {
            rejected.add("Конфликт CHECK/CALL")
            return FrameTextInterpretation(distinctActions.filterNot {
                it.action == ExactActionCandidate.CHECK || it.action == ExactActionCandidate.CALL
            }, distinctNumbers, rejected.distinct())
        }
        if (distinctActions.count { it.action == ExactActionCandidate.BET ||
                    it.action == ExactActionCandidate.RAISE || it.action == ExactActionCandidate.CONFIRM } > 1) {
            rejected.add("Конфликт BET/RAISE/CONFIRM")
            return FrameTextInterpretation(distinctActions.filterNot {
                it.action == ExactActionCandidate.BET || it.action == ExactActionCandidate.RAISE ||
                    it.action == ExactActionCandidate.CONFIRM
            }, distinctNumbers, rejected.distinct())
        }
        return FrameTextInterpretation(distinctActions, distinctNumbers, rejected.distinct())
    }
}
