package com.example.airemote

/**
 * End-to-end result preparation for an AUTHORIZED game-state provider.
 * It does not trust OS gesture callbacks, OCR, screenshots or ChatGPT replies.
 *
 * A verified card/action source is necessary but NOT sufficient for declaring
 * a move accepted. Two separately authenticated causal effect receipts still
 * go through AuthenticatedActionEffects + LiveGameActionGuard.
 */
data class AuthorizedPostActionFrame(
    val observation: AutonomousObservation,
    val sourceProof: ExternalTurnAttestation
)

enum class ProviderCycleRejection {
    STOP_OR_STALE, NO_AUTHENTIC_SOURCE, WRONG_GAME_OR_WINDOW,
    DIFFERENT_COMMAND, UNPROVEN_RESULT
}
data class ProviderCycleFrameResult(
    val frame: AuthorizedPostActionFrame? = null,
    val reason: ProviderCycleRejection? = null
) {
    val accepted get() = frame != null
}

class AuthorizedPostActionFrameGate(
    private val verifySource: ExternalAttestationVerifier,
    private val maximumAgeMs: Long = 1_200L
) {
    fun prepare(
        jpeg: FrameInfo,
        signed: IndependentlyAuthenticatedPokerFrame,
        receipt: PostActionReceipt,
        command: LiveCommand,
        expectedTableId: String,
        expectedTurnId: String,
        nowMs: Long,
        stopped: Boolean
    ): ProviderCycleFrameResult {
        fun reject(r: ProviderCycleRejection) = ProviderCycleFrameResult(reason = r)
        val proof = signed.attestation
        if (stopped || nowMs < jpeg.capturedAtMs ||
            nowMs - jpeg.capturedAtMs > maximumAgeMs || proof.expiresAtMs <= nowMs)
            return reject(ProviderCycleRejection.STOP_OR_STALE)
        if (!verifySource.verify(proof) || proof.providerId.isBlank() ||
            proof.proofId.isBlank() || proof.tableId.isBlank() ||
            proof.handId.isBlank() || proof.turnId.isBlank())
            return reject(ProviderCycleRejection.NO_AUTHENTIC_SOURCE)
        if (proof.tableId != expectedTableId || proof.turnId != expectedTurnId ||
            proof.handId != command.handKey || proof.epoch != jpeg.captureEpoch ||
            proof.packageName != jpeg.sourcePackage ||
            proof.windowGeneration != jpeg.windowGeneration ||
            proof.frameSequence != jpeg.seq || proof.capturedAtMs != jpeg.capturedAtMs)
            return reject(ProviderCycleRejection.WRONG_GAME_OR_WINDOW)
        if (receipt.commandId != command.id || receipt.action != command.action ||
            receipt.handKey != command.handKey ||
            receipt.epoch != command.captureEpoch ||
            receipt.windowGeneration != proof.windowGeneration ||
            receipt.providerId != proof.providerId || receipt.proofId != proof.proofId ||
            receipt.frameSequence != jpeg.seq ||
            receipt.capturedAtMs != jpeg.capturedAtMs)
            return reject(ProviderCycleRejection.DIFFERENT_COMMAND)
        if (signed.ownTurn || receipt.heroTurnClosed != true ||
            receipt.fingerprint != signed.stateFingerprint ||
            receipt.fingerprint == command.stateFingerprint ||
            receipt.frameSequence <= command.basedOnFrame ||
            receipt.capturedAtMs <= command.createdMs ||
            signed.stage !in setOf(HandStage.PREFLOP, HandStage.FLOP,
                HandStage.TURN, HandStage.RIVER))
            return reject(ProviderCycleRejection.UNPROVEN_RESULT)
        // A post-action state has no valid NEW button tap, even if a historical
        // action strip is still cached from before the gesture.
        val view = VerifiedLiveView(jpeg.captureEpoch, jpeg.seq, jpeg.capturedAtMs,
            proof.handId, signed.stateFingerprint,
            foregroundVerified = true, stageVerified = true,
            turnVerified = false, exactLabelsVerified = false,
            legalActions = emptySet())
        return ProviderCycleFrameResult(AuthorizedPostActionFrame(
            AutonomousObservation(view, proof.tableId, proof.handId, proof.turnId,
                proof.packageName, proof.windowGeneration,
                tableHandIndependentlyVerified = true,
                windowIndependentlyVerified = true, phase = signed.stage,
                timerRemainingMs = signed.timerRemainingMs,
                potChips = signed.potHundredths, callChips = signed.callHundredths,
                availableTaps = emptyMap()),
            proof))
    }
}
