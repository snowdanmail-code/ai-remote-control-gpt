package com.example.airemote

/** Synthetic fixtures: zero Android interaction, no real gameplay. */
object PlayerVisualIssueScenarios {
    @JvmStatic fun main(args: Array<String>) {
        var n = 0
        fun ok(v: Boolean, label: String) { check(v) { label }; n++ }
        fun reading(hole: List<String?>, board: List<String?> = emptyList(),
                    reason: String = "", complete: Boolean = false) =
            CardReading(hole, board, complete, 0.0, reason)
        ok(PlayerVisualIssueDiagnosis.cardFailure(reading(emptyList(),
            reason = "Неподдерживаемая ориентация/геометрия экрана")) ==
            CardFailureCategory.UNSUPPORTED_LAYOUT, "Geometry")
        ok(PlayerVisualIssueDiagnosis.cardFailure(reading(emptyList(),
            reason = "Не найдены 2 открытые карты игрока")) ==
            CardFailureCategory.HOLE_REGION_MISSING, "Hole ROI")
        ok(PlayerVisualIssueDiagnosis.cardFailure(reading(listOf(null, "Ah"))) ==
            CardFailureCategory.HOLE_GLYPHS_UNREADABLE, "Hole glyph")
        ok(PlayerVisualIssueDiagnosis.cardFailure(reading(
            listOf("Ah", "Kd"), listOf(null))) ==
            CardFailureCategory.COMMUNITY_GLYPHS_UNREADABLE, "Board glyph")
        ok(PlayerVisualIssueDiagnosis.cardFailure(reading(listOf("Ah", "Ah"),
            reason = "Повтор карты или недопустимое число общих карт")) ==
            CardFailureCategory.INVALID_OR_DUPLICATE_CARDS, "Duplicated")
        ok(PlayerVisualIssueDiagnosis.cardFailure(reading(listOf("Ah", "Kd"),
            complete = true)) == CardFailureCategory.COMPLETE_CANDIDATE, "Complete")
        fun line(text: String, y: Int) =
            PositionedOcrLine(text, NormalizedTextBox(360, y, 620, y + 20))
        val irrelevant = listOf(line("Welcome home", 500))
        val empty = FrameTextEvidence.parse(irrelevant)
        val noAction = PlayerOcrGeometryHints.inspect(irrelevant, empty)
        ok(noAction.hasAnyText && !noAction.actionWordAnywhere, "No action word")
        val realLabel = listOf(line("CALL 0.02 $", 935))
        val parsed = FrameTextEvidence.parse(realLabel)
        val hint = PlayerOcrGeometryHints.inspect(realLabel, parsed)
        ok(hint.actionWordAnywhere && hint.actionWordExpectedStrip &&
            hint.actionWordDecoratedOrRejected, "Decorated CALL")
        val misplaced = PlayerOcrGeometryHints.inspect(
            listOf(line("CHECK", 400)), empty)
        ok(misplaced.actionWordOutsideStrip &&
            !misplaced.actionWordExpectedStrip, "Out of strip")
        fun evidence(read: FrameTextInterpretation) = AutomaticOcrEvidence(
            OcrFrameTicket(2, 1, 1100, "demo", 0),
            read, OcrDiagnosticResult(OcrDiagnosticStatus.REJECTED, "test"),
            TableHandBindingResult(TableHandBindingStatus.UNVERIFIED_IDENTITY, "test"),
            HandStage.UNKNOWN, TurnDisplayState.UNKNOWN, 1200)
        ok(!PlayerOcrIncidentPolicy.conflictWorthLogging(evidence(empty)),
            "Empty OCR is not an OCR_CONFLICT incident")
        val wrong = FrameTextInterpretation(emptyList(), emptyList(), listOf("inconsistent"))
        ok(PlayerOcrIncidentPolicy.conflictWorthLogging(evidence(wrong)),
            "Conflicting parsed OCR still logged")
        println("PlayerVisualIssueScenarios: $n checks passed")
    }
}
