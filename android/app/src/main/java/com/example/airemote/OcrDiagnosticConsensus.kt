package com.example.airemote

/** Conservative, operator-visible agreement between TWO OCR readings of distinct JPEG frames.
 * A match is only a diagnostic hint. It is NEVER evidence of table/hand identity or permission.
 * Real pot/bet changes may cause a rejection; false consistency is intentionally avoided.
 */
enum class OcrDiagnosticStatus { REJECTED, WAITING_SECOND_FRAME, CONSISTENT_DIAGNOSTIC_ONLY }

data class OcrDiagnosticResult(val status: OcrDiagnosticStatus, val reason: String)

class OcrDiagnosticConsensus(private val maxGapMs: Long = 3000L) {
    private data class Candidate(
        val ticket: OcrFrameTicket,
        val actions: Set<ExactActionCandidate>,
        val numbers: Map<NumericCandidateKind, Long>
    )
    private var previous: Candidate? = null

    @Synchronized fun reset() { previous = null }

    @Synchronized fun observe(ticket: OcrFrameTicket?, reading: FrameTextInterpretation): OcrDiagnosticResult {
        if (ticket == null || ticket.captureEpoch < 0 || ticket.frameSequence <= 0 ||
            ticket.capturedAtMs < 0 || ticket.windowGeneration < 0 ||
            ticket.sourcePackage.isBlank() || reading.rejected.isNotEmpty() ||
            (reading.actions.isEmpty() && reading.numbers.isEmpty()) ||
            reading.actions.map { it.action }.distinct().size != reading.actions.size ||
            reading.numbers.map { it.kind }.distinct().size != reading.numbers.size ||
            reading.numbers.any { it.amount < 0L }) {
            reset()
            return OcrDiagnosticResult(OcrDiagnosticStatus.REJECTED, "Недостоверное OCR-наблюдение")
        }
        val current = Candidate(ticket, reading.actions.map { it.action }.toSet(),
            reading.numbers.associate { it.kind to it.amount })
        val old = previous
        if (old == null) {
            previous = current
            return OcrDiagnosticResult(OcrDiagnosticStatus.WAITING_SECOND_FRAME, "Нужен другой кадр")
        }
        val delta = ticket.capturedAtMs - old.ticket.capturedAtMs
        val sameOrigin = ticket.captureEpoch == old.ticket.captureEpoch &&
            ticket.sourcePackage == old.ticket.sourcePackage &&
            ticket.windowGeneration == old.ticket.windowGeneration
        val sequential = ticket.frameSequence > old.ticket.frameSequence &&
            delta in 1L..maxGapMs
        if (!sameOrigin || !sequential) {
            // A duplicate/reordered frame MUST NOT become a new seed for implicit confirmation.
            reset()
            return OcrDiagnosticResult(OcrDiagnosticStatus.REJECTED, "Смена окна, дублирование или разрыв кадров")
        }
        val sameLabels = current.actions == old.actions &&
            current.numbers.keys == old.numbers.keys
        val amountsAgree = listOf(NumericCandidateKind.POT, NumericCandidateKind.CALL_AMOUNT).all {
            old.numbers[it] == current.numbers[it]
        }
        val oldTimer = old.numbers[NumericCandidateKind.TIMER_SECONDS]
        val newTimer = current.numbers[NumericCandidateKind.TIMER_SECONDS]
        val timerAgrees = if (oldTimer == null && newTimer == null) true
            else if (oldTimer == null || newTimer == null) false
            else newTimer <= oldTimer && oldTimer - newTimer <= (delta + 999L) / 1000L + 2L
        if (!sameLabels || !amountsAgree || !timerAgrees) {
            // Do not certify a new hand from a changed pot/timer; wait for fresh independent frames.
            previous = current
            return OcrDiagnosticResult(OcrDiagnosticStatus.WAITING_SECOND_FRAME,
                "Изменились подписи/суммы/таймер: требуется повторное наблюдение")
        }
        previous = current
        return OcrDiagnosticResult(OcrDiagnosticStatus.CONSISTENT_DIAGNOSTIC_ONLY,
            "Два кадра OCR согласованы; идентичность стола и раздачи НЕ подтверждена")
    }
}
