package com.example.airemote

import android.content.Context
import android.os.SystemClock

/**
 * The missing production INBOUND connector. A provider must be independently
 * authenticated and expressly authorized by the game/platform. Neither OAuth
 * sign-in to ChatGPT nor screenshots implement this interface.
 */
interface AuthorizedVirtualPokerProvider {
    fun verifyTurnProof(proof: ExternalTurnAttestation): Boolean
    fun verifyEffectProof(receipt: PostActionReceipt): Boolean
}

enum class PlayerFeedStatus {
    NO_PROVIDER, STOPPED, REJECTED, WAITING_FOR_SECOND_EFFECT,
    ACCEPTED_TO_EXISTING_AUTONOMOUS_LOOP, EFFECT_SUBMITTED_TO_LOOP
}
data class PlayerFeedResult(val status: PlayerFeedStatus, val detail: String,
                            val loopState: AutoLoopState? = null)

/**
 * Connects authenticated state -> original exact JPEG -> player/card/control
 * reader -> VerifiedLocalPokerStrategy -> VerifiedAndroidTransport, and
 * separately connects authenticated post-gesture receipts back into the
 * original one-action-per-turn control loop.
 *
 * No provider is bundled with the app, therefore nothing in this class may
 * manufacture proof or turn on accessibility gestures.
 */
object OnDeviceAuthorizedPlayerFeed {
    @Volatile private var provider: AuthorizedVirtualPokerProvider? = null
    @Volatile private var joiner: AuthenticatedPlayerFrameJoiner? = null

    @Synchronized fun install(authorized: AuthorizedVirtualPokerProvider) {
        OnDeviceAutonomousRuntime.stop("Замена авторизованного игрового источника")
        provider = authorized
        val verifier = ExternalAttestationVerifier { proof -> authorized.verifyTurnProof(proof) }
        joiner = AuthenticatedPlayerFrameJoiner(verifier)
        OnDeviceAutonomousRuntime.installExternalIdentityVerifier(verifier)
        OnDeviceEffectAuthority.install(IndependentEffectVerifier { receipt ->
            authorized.verifyEffectProof(receipt)
        })
        OnDeviceAutonomousRuntime.ensureLocalStrategy()
    }

    @Synchronized fun disconnect() {
        OnDeviceAutonomousRuntime.stop("Авторизованный источник отключён")
        provider = null
        joiner = null
        OnDeviceAutonomousRuntime.clearExternalIdentityVerifier()
        OnDeviceEffectAuthority.clear()
    }

    fun available(): Boolean = provider != null && joiner != null

    private fun validSession(context: Context, frame: FrameInfo?,
                             proof: ExternalTurnAttestation): Boolean {
        val origin = FrameStore.windowOrigin
        val root = LiveForegroundProbe.snapshot()
        return provider != null && joiner != null && frame != null &&
            !CaptureObservationMode.isSpectator() &&
            !OperatorStopStore.isStopped(context) &&
            FrameStore.isCapturing && LiveGamePermissionGate.permitted() &&
            origin?.safeForLocalDiagnostics == true &&
            origin.windowGeneration == proof.windowGeneration &&
            frame.captureEpoch == proof.epoch &&
            frame.windowGeneration == proof.windowGeneration &&
            frame.sourcePackage == proof.packageName &&
            frame.seq == proof.frameSequence &&
            frame.capturedAtMs == proof.capturedAtMs &&
            root?.packageName == proof.packageName &&
            root.windowGeneration == proof.windowGeneration &&
            LiveGamePermissionGate.packageName() == proof.packageName
    }

    /** Called solely by the installed provider for a PROVEN current game turn. */
    @Synchronized fun acceptTurn(context: Context,
                                 packet: IndependentlyAuthenticatedPokerFrame,
                                 displayWidth: Int, displayHeight: Int): PlayerFeedResult {
        val j = joiner ?: return PlayerFeedResult(PlayerFeedStatus.NO_PROVIDER, "Нет игрового источника")
        val frame = FrameStore.latest
        val proof = packet.attestation
        if (!validSession(context, frame, proof))
            return PlayerFeedResult(PlayerFeedStatus.STOPPED, "STOP, чужое окно или устаревший кадр")
        val f = requireNotNull(frame)
        val viewport = CaptureViewport(f.width, f.height,
            displayWidth, displayHeight, 0, 0, displayWidth, displayHeight)
        val result = j.combine(f, f.playerCardsAtCapture,
            FrameStore.lastActionStrip, packet, viewport,
            SystemClock.elapsedRealtime(), OperatorStopStore.isStopped(context))
        if (!result.accepted)
            return PlayerFeedResult(PlayerFeedStatus.REJECTED, result.error)
        val loop = OnDeviceAutonomousRuntime.acceptVerifiedObservation(
            context.applicationContext, result.observation, result.sourceProof)
        return PlayerFeedResult(PlayerFeedStatus.ACCEPTED_TO_EXISTING_AUTONOMOUS_LOOP,
            loop.explanation, loop.state)
    }

    /**
     * Two DISTINCT provider-verified settled receipts are needed. No inference
     * from the Android GestureResultCallback, OCR, button disappearance or a
     * changed JPEG alone. Never resubmit the old command after uncertainty.
     */
    @Synchronized fun acceptSettledEffect(context: Context,
                                          proof: ExternalTurnAttestation,
                                          receipt: PostActionReceipt): PlayerFeedResult {
        val signed = provider ?: return PlayerFeedResult(PlayerFeedStatus.NO_PROVIDER, "Нет источника")
        val frame = FrameStore.latest
        if (!validSession(context, frame, proof))
            return PlayerFeedResult(PlayerFeedStatus.STOPPED, "Нет свежего кадра после действия")
        val f = requireNotNull(frame)
        val command = OnDeviceLiveActionControl.guard.pendingCommandForEffect()
            ?: return PlayerFeedResult(PlayerFeedStatus.REJECTED, "Нет ожидающей подтверждения команды")
        val now = SystemClock.elapsedRealtime()
        if (!signed.verifyTurnProof(proof) || !signed.verifyEffectProof(receipt) ||
            proof.providerId != receipt.providerId ||
            proof.handId != command.handKey || receipt.handKey != command.handKey ||
            proof.epoch != command.captureEpoch ||
            receipt.epoch != command.captureEpoch ||
            receipt.windowGeneration != proof.windowGeneration ||
            receipt.frameSequence != frame.seq ||
            receipt.capturedAtMs != frame.capturedAtMs ||
            receipt.fingerprint.isBlank() ||
            receipt.action != command.action ||
            receipt.commandId != command.id ||
            receipt.capturedAtMs <= command.sourceCapturedAtMs) {
            OnDeviceAutonomousRuntime.stop("Недостоверный результат хода")
            return PlayerFeedResult(PlayerFeedStatus.REJECTED, "Эффект не привязан к отправленной команде")
        }
        val effect = OnDeviceEffectAuthority.inspect(command, receipt, now,
            OperatorStopStore.isStopped(context))
        if (effect.status == EffectGateStatus.COLLECTING)
            return PlayerFeedResult(PlayerFeedStatus.WAITING_FOR_SECOND_EFFECT,
                "Первое подтверждение принято; нужен ещё один подписанный кадр")
        if (effect.status != EffectGateStatus.CONFIRMED_EFFECT || effect.ticket == null) {
            OnDeviceAutonomousRuntime.stop("Эффект действия не подтверждён")
            return PlayerFeedResult(PlayerFeedStatus.REJECTED, effect.reason)
        }
        // This view intentionally has no visible 'own turn' or button labels:
        // the game may already have removed all action buttons after a tap.
        // The command's authenticated effect, not new move permissions, is
        // all that can be confirmed here.
        val view = VerifiedLiveView(f.captureEpoch, f.seq, f.capturedAtMs,
            proof.handId, receipt.fingerprint,
            foregroundVerified = true, stageVerified = true,
            turnVerified = false, exactLabelsVerified = false,
            legalActions = emptySet(), verifiedEffectTicket = effect.ticket)
        val obs = AutonomousObservation(view, proof.tableId, proof.handId,
            proof.turnId, proof.packageName, proof.windowGeneration,
            tableHandIndependentlyVerified = true,
            windowIndependentlyVerified = true,
            phase = HandStage.UNKNOWN, timerRemainingMs = null, potChips = null,
            callChips = null, availableTaps = emptyMap())
        val loop = OnDeviceAutonomousRuntime.acceptVerifiedObservation(
            context.applicationContext, obs, proof)
        return PlayerFeedResult(PlayerFeedStatus.EFFECT_SUBMITTED_TO_LOOP,
            loop.explanation, loop.state)
    }
}
