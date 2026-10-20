package com.example.airemote

/** Read-only result of a live JPEG OCR pass. No recognized text can authorize a gesture. */
data class AutomaticOcrEvidence(
    val ticket: OcrFrameTicket,
    val interpretation: FrameTextInterpretation,
    val agreement: OcrDiagnosticResult,
    val identity: TableHandBindingResult,
    val probableStage: HandStage,
    val turnDisplay: TurnDisplayState,
    val processedAtMs: Long
) {
    /** OCR consensus and apparent table graphics are deliberately NOT identity proof. */
    val automationReady: Boolean get() = false
    fun summary(): String =
        "Фоновое OCR: кадр ${ticket.frameSequence}; " +
        "кнопки ${interpretation.actions.joinToString { it.action.name }.ifEmpty { "?" }}; " +
        "числа ${interpretation.numbers.joinToString { "${it.kind}=${it.amount}" }.ifEmpty { "?" }}; " +
        "стадия $probableStage; панель $turnDisplay; " +
        "OCR ${agreement.status}; стол/раздача ${identity.status}; касания запрещены"
}

/** Pure Kotlin processing used only after atomic JPEG provenance validation. */
class AutomaticOcrEvidenceAssembler {
    private val consensus = OcrDiagnosticConsensus()
    private val identityGuard = TableHandIdentityGuard()
    private var previousEpoch = -1L
    private var previousWindow = -1L
    private var previousPackage = ""

    @Synchronized fun reset() {
        consensus.reset()
        identityGuard.reset()
        previousEpoch = -1L
        previousWindow = -1L
        previousPackage = ""
    }

    @Synchronized fun inspect(
        ticket: OcrFrameTicket,
        reading: FrameTextInterpretation,
        stage: CardStageAssessment?,
        turn: ConfirmedTurnDisplay?,
        nowMs: Long
    ): AutomaticOcrEvidence? {
        if (ticket.captureEpoch < 0L || ticket.frameSequence <= 0L ||
            ticket.capturedAtMs < 0L || ticket.sourcePackage.isBlank() ||
            ticket.windowGeneration < 0L || nowMs < ticket.capturedAtMs ||
            nowMs - ticket.capturedAtMs > 1_500L) {
            reset()
            return null
        }
        if (previousEpoch != ticket.captureEpoch ||
            previousWindow != ticket.windowGeneration || previousPackage != ticket.sourcePackage) {
            reset()
            previousEpoch = ticket.captureEpoch
            previousWindow = ticket.windowGeneration
            previousPackage = ticket.sourcePackage
        }
        val agreement = consensus.observe(ticket, reading)
        // The frame alone cannot authenticate 1win, table ID, hand ID or permissions.
        val identity = identityGuard.inspect(null, ticket, agreement, nowMs, false)
        val stageHint = stage?.takeIf { it.frameSequence > 0L &&
            it.probableStage != HandStage.UNKNOWN }?.probableStage ?: HandStage.UNKNOWN
        val turnHint = turn?.takeIf { it.captureEpoch == ticket.captureEpoch &&
            it.capturedAtMs in (ticket.capturedAtMs - 1_500L)..ticket.capturedAtMs }
            ?.state ?: TurnDisplayState.UNKNOWN
        return AutomaticOcrEvidence(ticket, reading, agreement, identity,
            stageHint, turnHint, nowMs)
    }
}
