package com.example.airemote

/** An identifier received from an independently validated source, NOT OCR, card images,
 * Android package name, a user-entered value or an inferred stage of a hand.
 * No such trusted producer is connected in this release. A caller-supplied boolean does
 * not itself verify a producer: the integration must implement its own authentication.
 */
data class IndependentTableHandAssertion(
    val tableId: String,
    val handId: String,
    val captureEpoch: Long,
    val sourcePackage: String,
    val windowGeneration: Long,
    val issuedAtMs: Long,
    val expiresAtMs: Long,
    val independentSourceVerified: Boolean
)

enum class TableHandBindingStatus {
    UNVERIFIED_IDENTITY, REJECTED, WAITING_INDEPENDENT_FRAME, MATCHED_DIAGNOSTIC_ONLY
}

data class TableHandBindingResult(val status: TableHandBindingStatus, val reason: String)

/** Strict, read-only binding between an OCR diagnostic and a separately attested table/hand.
 * Never treats consensus, an Android package, two equal pots or card count as identity.
 * Never grants tap, game strategy or remote command authority.
 */
class TableHandIdentityGuard(
    private val maxFrameAgeMs: Long = 1_500L,
    private val maxConfirmationGapMs: Long = 2_000L
) {
    private data class Last(val proof: IndependentTableHandAssertion, val ticket: OcrFrameTicket)
    private var previous: Last? = null

    @Synchronized fun reset() { previous = null }

    @Synchronized fun inspect(
        assertion: IndependentTableHandAssertion?,
        ticket: OcrFrameTicket?,
        ocrAgreement: OcrDiagnosticResult,
        nowMs: Long,
        stopped: Boolean
    ): TableHandBindingResult {
        if (stopped) {
            reset()
            return TableHandBindingResult(TableHandBindingStatus.REJECTED, "STOP: контекст удалён")
        }
        if (assertion == null || !assertion.independentSourceVerified) {
            reset()
            return TableHandBindingResult(TableHandBindingStatus.UNVERIFIED_IDENTITY,
                "Нет независимого подтверждения идентификатора стола и раздачи")
        }
        if (ticket == null || !validId(assertion.tableId) || !validId(assertion.handId) ||
            !validPackage(assertion.sourcePackage) ||
            assertion.captureEpoch < 0 || assertion.windowGeneration < 0 ||
            assertion.issuedAtMs < 0 || assertion.expiresAtMs <= assertion.issuedAtMs ||
            assertion.captureEpoch != ticket.captureEpoch ||
            assertion.sourcePackage != ticket.sourcePackage ||
            assertion.windowGeneration != ticket.windowGeneration ||
            ticket.frameSequence <= 0 || ticket.capturedAtMs < 0 ||
            ticket.capturedAtMs < assertion.issuedAtMs ||
            ticket.capturedAtMs > assertion.expiresAtMs ||
            nowMs < ticket.capturedAtMs || nowMs - ticket.capturedAtMs > maxFrameAgeMs ||
            nowMs > assertion.expiresAtMs) {
            reset()
            return TableHandBindingResult(TableHandBindingStatus.REJECTED,
                "Несовпадение контекста/окна, недействительное или устаревшее подтверждение")
        }
        if (ocrAgreement.status == OcrDiagnosticStatus.REJECTED) {
            reset()
            return TableHandBindingResult(TableHandBindingStatus.REJECTED, "OCR отклонён")
        }
        val old = previous
        if (old != null) {
            if (old.proof.tableId != assertion.tableId ||
                old.proof.handId != assertion.handId ||
                old.proof.captureEpoch != assertion.captureEpoch ||
                old.proof.sourcePackage != assertion.sourcePackage ||
                old.proof.windowGeneration != assertion.windowGeneration ||
                ticket.frameSequence <= old.ticket.frameSequence ||
                ticket.capturedAtMs <= old.ticket.capturedAtMs ||
                ticket.capturedAtMs - old.ticket.capturedAtMs > maxConfirmationGapMs ||
                assertion.issuedAtMs <= old.proof.issuedAtMs ||
                assertion.issuedAtMs <= old.ticket.capturedAtMs ||
                ticket.capturedAtMs > old.proof.expiresAtMs ||
                assertion.issuedAtMs > ticket.capturedAtMs) {
                // Do not use the changed or out-of-order observation as a new seed.
                reset()
                return TableHandBindingResult(TableHandBindingStatus.REJECTED,
                    "Смена стола/раздачи/окна либо нарушение последовательности: сброс")
            }
            // Do not inherit a prior consensus. Only the consensus for this frame counts.
            previous = Last(assertion, ticket)
            if (ocrAgreement.status == OcrDiagnosticStatus.CONSISTENT_DIAGNOSTIC_ONLY) {
                return TableHandBindingResult(TableHandBindingStatus.MATCHED_DIAGNOSTIC_ONLY,
                    "Идентификаторы согласованы в двух кадрах; только диагностика, не разрешение действий")
            }
            return TableHandBindingResult(TableHandBindingStatus.WAITING_INDEPENDENT_FRAME,
                "OCR изменился: требуется новое независимое совпадение")
        }
        previous = Last(assertion, ticket)
        return TableHandBindingResult(TableHandBindingStatus.WAITING_INDEPENDENT_FRAME,
            "Ожидание второго независимого кадра для идентификаторов стола и раздачи")
    }

    private fun validId(value: String): Boolean = value.length in 1..128 &&
        value.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' || it == ':' }

    private fun validPackage(value: String): Boolean =
        Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+").matches(value)
}
