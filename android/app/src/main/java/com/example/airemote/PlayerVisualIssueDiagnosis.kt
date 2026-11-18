package com.example.airemote

/** Read-only reasons for card failure, not proofs of game state or permissions. */
enum class CardFailureCategory {
    COMPLETE_CANDIDATE, UNSUPPORTED_LAYOUT, HOLE_REGION_MISSING,
    HOLE_GLYPHS_UNREADABLE, COMMUNITY_GLYPHS_UNREADABLE,
    INVALID_OR_DUPLICATE_CARDS, OTHER_INCOMPLETE
}
object PlayerVisualIssueDiagnosis {
    fun cardFailure(reading: CardReading): CardFailureCategory = when {
        reading.complete -> CardFailureCategory.COMPLETE_CANDIDATE
        reading.reason.contains("Неподдерживаемая ориентация") ->
            CardFailureCategory.UNSUPPORTED_LAYOUT
        reading.reason.contains("Повтор карты") ->
            CardFailureCategory.INVALID_OR_DUPLICATE_CARDS
        reading.hole.size != 2 -> CardFailureCategory.HOLE_REGION_MISSING
        reading.hole.any { it == null } -> CardFailureCategory.HOLE_GLYPHS_UNREADABLE
        reading.community.any { it == null } -> CardFailureCategory.COMMUNITY_GLYPHS_UNREADABLE
        else -> CardFailureCategory.OTHER_INCOMPLETE
    }
}

/** Whitelisted aggregate OCR geometry hints; no OCR text or images are retained. */
data class OcrGeometryHints(
    val hasAnyText: Boolean,
    val actionWordAnywhere: Boolean,
    val actionWordExpectedStrip: Boolean,
    val actionWordOutsideStrip: Boolean,
    val actionWordDecoratedOrRejected: Boolean,
    val potWordAnywhere: Boolean,
    val callWordAnywhere: Boolean
)
object PlayerOcrGeometryHints {
    private val actionWord = Regex(
        "(?iu)(?<![\\p{L}])(?:fold|check|call|bet|raise|confirm|сброс|сбросить|пас|чек|пропустить|колл|уравнять|ответить|ставка|поставить|рейз|повысить|подтвердить|готово)(?![\\p{L}])")
    private val potWord = Regex("(?iu)(?<![\\p{L}])(?:pot|банк)(?![\\p{L}])")
    private val callWord = Regex("(?iu)(?<![\\p{L}])(?:call|колл|уравнять)(?![\\p{L}])")
    fun inspect(lines: List<PositionedOcrLine>, parsed: FrameTextInterpretation): OcrGeometryHints {
        val safe = lines.take(100).filter { it.box.valid() && it.text.length <= 80 }
        val matches = safe.filter { actionWord.containsMatchIn(it.text) }
        return OcrGeometryHints(
            safe.isNotEmpty(),matches.isNotEmpty(),
            matches.any { it.box.centerY() in 915..985 },
            matches.any { it.box.centerY() !in 915..985 },
            matches.isNotEmpty() && parsed.actions.isEmpty(),
            safe.any { potWord.containsMatchIn(it.text) },
            safe.any { callWord.containsMatchIn(it.text) })
    }
}

/** No action or number candidate means EMPTY OCR, not a conflict. */
object PlayerOcrIncidentPolicy {
    fun conflictWorthLogging(result: AutomaticOcrEvidence?): Boolean =
        result != null && result.agreement.status == OcrDiagnosticStatus.REJECTED &&
            (result.interpretation.actions.isNotEmpty() ||
                result.interpretation.numbers.isNotEmpty() ||
                result.interpretation.rejected.isNotEmpty())
}
