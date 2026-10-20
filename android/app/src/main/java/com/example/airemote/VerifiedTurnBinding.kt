package com.example.airemote

/** Evidence from distinct providers must agree on the SAME captured frame and window.
 * The provider verifier must authenticate its provenance externally; a boolean field
 * or stable OCR text is never a credential. This is a read-only gate.
 */
data class ExternalTurnAttestation(
    val providerId: String,
    val proofId: String,
    val tableId: String,
    val handId: String,
    val turnId: String,
    val packageName: String,
    val epoch: Long,
    val windowGeneration: Long,
    val frameSequence: Long,
    val capturedAtMs: Long,
    val expiresAtMs: Long
)

fun interface ExternalAttestationVerifier {
    /** Production must validate origin and freshness of a proof, not echo an input flag. */
    fun verify(attestation: ExternalTurnAttestation): Boolean
}

enum class TurnBindingStatus { REJECTED, WAITING, CONSISTENT_READ_ONLY }

data class TurnBindingResult(
    val status: TurnBindingStatus,
    val reason: String,
    val tableId: String? = null,
    val handId: String? = null,
    val turnId: String? = null
) {
    /** Verifying identity alone does not grant a gesture, legal action or amount. */
    val authorizesTap: Boolean get() = false
}

class VerifiedTurnBinding(
    private val verifier: ExternalAttestationVerifier,
    private val maxAgeMs: Long = 1_200,
    private val maxGapMs: Long = 2_500
) {
    private var prior: ExternalTurnAttestation? = null

    @Synchronized fun reset() { prior = null }

    @Synchronized fun inspect(
        proof: ExternalTurnAttestation?,
        view: VerifiedLiveView?,
        activeWindowPackage: String?,
        activeWindowGeneration: Long?,
        visualTurn: TurnCycleReport?,
        nowMs: Long,
        stopped: Boolean
    ): TurnBindingResult {
        fun reject(why: String): TurnBindingResult {
            reset(); return TurnBindingResult(TurnBindingStatus.REJECTED, why)
        }
        if (stopped || proof == null || view == null || activeWindowGeneration == null) return reject("STOP or missing evidence")
        if (proof.providerId.isBlank() || proof.proofId.isBlank() ||
            proof.tableId.isBlank() || proof.handId.isBlank() || proof.turnId.isBlank() ||
            proof.providerId.length > 96 || proof.proofId.length > 128 ||
            proof.packageName != activeWindowPackage || proof.windowGeneration != activeWindowGeneration ||
            proof.epoch != view.captureEpoch || proof.frameSequence != view.frameSequence ||
            proof.capturedAtMs != view.capturedAtMs || proof.handId != view.handKey ||
            proof.epoch < 0L || proof.windowGeneration < 0L || proof.frameSequence <= 0L ||
            nowMs < proof.capturedAtMs || nowMs - proof.capturedAtMs > maxAgeMs ||
            proof.expiresAtMs < nowMs || proof.expiresAtMs <= proof.capturedAtMs ||
            !view.foregroundVerified || !view.stageVerified || !view.turnVerified ||
            !view.exactLabelsVerified || view.legalActions.isEmpty() ||
            visualTurn?.phase != TurnCyclePhase.VISIBLE_DIAGNOSTIC ||
            visualTurn.latestFrame != proof.frameSequence ||
            !verifier.verify(proof)) return reject("Attestation, frame or turn invalid")
        val old = prior
        if (old == null) {
            prior = proof
            return TurnBindingResult(TurnBindingStatus.WAITING, "Need a second independent frame")
        }
        if (proof.packageName != old.packageName || proof.tableId != old.tableId ||
            proof.handId != old.handId || proof.turnId != old.turnId ||
            proof.epoch != old.epoch || proof.windowGeneration != old.windowGeneration ||
            proof.providerId != old.providerId || proof.proofId == old.proofId ||
            proof.frameSequence <= old.frameSequence || proof.capturedAtMs <= old.capturedAtMs ||
            proof.capturedAtMs - old.capturedAtMs > maxGapMs) return reject("Identity changed or replayed proof")
        prior = proof
        return TurnBindingResult(TurnBindingStatus.CONSISTENT_READ_ONLY,
            "Two authenticated proofs match; action legality remains separate",
            proof.tableId, proof.handId, proof.turnId)
    }
}

/** Strict JPEG pixel -> Android display transform. It is not a source of measured
 * button bounds: only separately calibrated rectangles are acceptable.
 */
data class CaptureViewport(
    val jpegWidth: Int, val jpegHeight: Int,
    val displayWidth: Int, val displayHeight: Int,
    val contentLeft: Int, val contentTop: Int,
    val contentWidth: Int, val contentHeight: Int,
    val rotated: Boolean = false
) {
    fun valid(): Boolean = !rotated && jpegWidth > 0 && jpegHeight > 0 &&
        displayWidth > 0 && displayHeight > 0 && contentLeft >= 0 && contentTop >= 0 &&
        contentWidth > 0 && contentHeight > 0 &&
        contentLeft.toLong() + contentWidth <= displayWidth &&
        contentTop.toLong() + contentHeight <= displayHeight &&
        // Do not assume a crop or letterbox is identity-mapped.
        jpegWidth.toLong() * contentHeight == jpegHeight.toLong() * contentWidth
}

object VerifiedScreenProjection {
    fun project(
        cue: ActionStripCue,
        viewport: CaptureViewport,
        sourcePackage: String,
        windowGeneration: Long
    ): VerifiedTapArea? {
        if (!viewport.valid() || !cue.measuredButton.valid() || sourcePackage.isBlank() ||
            windowGeneration < 0L || cue.action == ExactActionCandidate.CONFIRM) return null
        val action = try { LiveAction.valueOf(cue.action.name) } catch (_: IllegalArgumentException) { return null }
        fun x(n: Int): Int = viewport.contentLeft + (n.toLong() * viewport.contentWidth / 1000L).toInt()
        fun y(n: Int): Int = viewport.contentTop + (n.toLong() * viewport.contentHeight / 1000L).toInt()
        val b = cue.measuredButton
        val left = x(b.left); val right = x(b.right)
        val top = y(b.top); val bottom = y(b.bottom)
        if (right <= left || bottom <= top) return null
        val area = VerifiedTapArea(left, top, right, bottom,
            (left + right) / 2, (top + bottom) / 2,
            viewport.displayWidth, viewport.displayHeight,
            action, cue.observedText, sourcePackage, windowGeneration)
        return if (LiveTapSafety.verify(area, LiveCommand("geometry-only", "no-hand-proof", action,
                0, 1, "no-state-proof", 0), sourcePackage, 0, 0,
                viewport.displayWidth, viewport.displayHeight, sourcePackage) == TapSafetyResult.VALID)
            area else null
    }
}
