package com.example.airemote

/** Read-only proof that the OCR callback still belongs to exactly the requested JPEG.
 * Does NOT establish application identity, table identity, hand identity or the right to act.
 */
data class OcrFrameTicket(
    val captureEpoch: Long,
    val frameSequence: Long,
    val capturedAtMs: Long,
    val sourcePackage: String,
    val windowGeneration: Long
)

enum class OcrFrameCheck { VALID_DIAGNOSTIC, REJECTED }

object OcrFrameProvenance {
    private const val MAX_AGE_MS = 8000L

    fun ticket(frame: FrameInfo, origin: WindowOriginAssessment?): OcrFrameTicket? {
        if (origin?.safeForLocalDiagnostics != true ||
            frame.captureEpoch < 0 || frame.seq <= 0 || frame.capturedAtMs < 0 ||
            frame.sourcePackage.isNullOrBlank() || frame.windowGeneration == null ||
            origin.captureEpoch != frame.captureEpoch ||
            origin.observedPackage != frame.sourcePackage ||
            origin.windowGeneration != frame.windowGeneration ||
            origin.localFrameSequence <= 0L) return null
        return OcrFrameTicket(frame.captureEpoch, frame.seq, frame.capturedAtMs,
            frame.sourcePackage, frame.windowGeneration)
    }

    fun check(
        ticket: OcrFrameTicket?, currentFrame: FrameInfo?,
        currentOrigin: WindowOriginAssessment?, currentCaptureEpoch: Long,
        nowMs: Long, stop: Boolean
    ): OcrFrameCheck {
        if (stop || ticket == null || currentFrame == null ||
            currentOrigin?.safeForLocalDiagnostics != true ||
            currentCaptureEpoch != ticket.captureEpoch ||
            currentFrame.captureEpoch != ticket.captureEpoch ||
            currentFrame.seq != ticket.frameSequence ||
            currentFrame.capturedAtMs != ticket.capturedAtMs ||
            currentFrame.sourcePackage != ticket.sourcePackage ||
            currentFrame.windowGeneration != ticket.windowGeneration ||
            currentOrigin.captureEpoch != ticket.captureEpoch ||
            currentOrigin.observedPackage != ticket.sourcePackage ||
            currentOrigin.windowGeneration != ticket.windowGeneration ||
            ticket.frameSequence <= 0L || ticket.capturedAtMs < 0 ||
            nowMs < ticket.capturedAtMs || nowMs - ticket.capturedAtMs > MAX_AGE_MS) {
            return OcrFrameCheck.REJECTED
        }
        return OcrFrameCheck.VALID_DIAGNOSTIC
    }
}
