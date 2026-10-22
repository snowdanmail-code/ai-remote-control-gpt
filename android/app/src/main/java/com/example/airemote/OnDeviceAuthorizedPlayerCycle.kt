package com.example.airemote

import android.content.Context
import android.os.SystemClock

/**
 * The missing Android glue between existing independent modules:
 *
 * captured JPEG+cards+calibrated controls + authorized provider frame
 *   -> AuthenticatedPlayerFrameJoiner
 *   -> OnDeviceAutonomousRuntime (local strategy + verified dispatch)
 *   -> independently authenticated receipt #1/#2
 *   -> AuthenticatedActionEffects
 *   -> OnDeviceAutonomousRuntime (result confirmation, NOT a second tap)
 *
 * NO game-state provider ships in the APK: a real permitted, authenticated
 * provider is still needed. ChatGPT sign-in is not such a provider.
 * No screenshots or credentials are uploaded by this bridge.
 */
enum class AuthorizedCycleStage {
    NOT_CONFIGURED, STOPPED, FRAME_REJECTED, TURN_ACCEPTED,
    AWAITING_INDEPENDENT_RESULT, RESULT_REJECTED, RESULT_CONFIRMED
}
data class AuthorizedCycleStatus(
    val stage: AuthorizedCycleStage, val description: String,
    val loopState: AutoLoopState? = null
)

object OnDeviceAuthorizedPlayerCycle {
    @Volatile private var sourceVerifier: ExternalAttestationVerifier? = null
    private var joiner: AuthenticatedPlayerFrameJoiner? = null
    private var resultFrameGate: AuthorizedPostActionFrameGate? = null
    private var activeTableId: String? = null
    private var activeTurnId: String? = null

    @Volatile private var last = AuthorizedCycleStatus(
        AuthorizedCycleStage.NOT_CONFIGURED, "Нет разрешённого внешнего источника игры")

    fun status(): AuthorizedCycleStatus = last
    fun providerConnected(): Boolean = sourceVerifier != null

    /**
     * A provider integration installs its own real credential verification.
     * Only while STOP is engaged and capture is inactive; never hot-swap proof
     * authority or strategy while a possible tap can still be in flight.
     */
    @Synchronized fun configure(
        context: Context,
        providerVerifier: ExternalAttestationVerifier,
        effectVerifier: IndependentEffectVerifier
    ): Boolean {
        if (!OperatorStopStore.isStopped(context) || FrameStore.isCapturing) return false
        OnDeviceAutonomousRuntime.stop("Подключение внешнего источника")
        sourceVerifier = null
        joiner = null
        resultFrameGate = null
        OnDeviceEffectAuthority.clear()
        activeTableId = null
        activeTurnId = null
        OnDeviceAutonomousRuntime.installExternalIdentityVerifier(providerVerifier)
        OnDeviceEffectAuthority.install(effectVerifier)
        sourceVerifier = providerVerifier
        joiner = AuthenticatedPlayerFrameJoiner(providerVerifier)
        resultFrameGate = AuthorizedPostActionFrameGate(providerVerifier)
        last = AuthorizedCycleStatus(AuthorizedCycleStage.STOPPED,
            "Источник подключён; требуется операторское разрешение и свежий ход")
        return true
    }

    /**
     * Receive ONE independently authenticated game-state update. The
     * transport still verifies STOP, hand, legal actions, window, button
     * coordinates and timestamp immediately before any actual Android tap.
     */
    @Synchronized fun acceptCurrentTurn(
        context: Context,
        packet: IndependentlyAuthenticatedPokerFrame,
        measuredViewport: CaptureViewport
    ): AuthorizedCycleStatus {
        val validator = joiner ?: return update(AuthorizedCycleStage.NOT_CONFIGURED,
            "Отсутствует подтверждённый поставщик состояния 1win")
        if (CaptureObservationMode.isSpectator() ||
            OperatorStopStore.isStopped(context) || !FrameStore.isCapturing)
            return update(AuthorizedCycleStage.STOPPED, "STOP или нет захвата")
        val frame = FrameStore.latest ?: return update(
            AuthorizedCycleStage.FRAME_REJECTED, "Нет текущего JPEG")
        val result = validator.combine(frame, frame.playerCardsAtCapture,
            FrameStore.lastActionStrip, packet, measuredViewport,
            SystemClock.elapsedRealtime(), false)
        if (!result.accepted) return update(
            AuthorizedCycleStage.FRAME_REJECTED, result.error)
        val observation = requireNotNull(result.observation)
        val proof = requireNotNull(result.sourceProof)
        val status = OnDeviceAutonomousRuntime.acceptVerifiedObservation(
            context, observation, proof)
        // A packet admitted by the joiner may still be WAITING_FOR_PROOF in
        // VerifiedTurnBinding until TWO independent proofs match.
        activeTableId = observation.tableId
        activeTurnId = observation.turnId
        last = AuthorizedCycleStatus(AuthorizedCycleStage.TURN_ACCEPTED,
            status.explanation, status.state)
        return last
    }

    /**
     * A receipt is a signed statement of game effect, not a gesture callback.
     * Never accept a changed screen bitmap or OS onCompleted as proof.
     * No blind retries on failures or delays.
     */
    @Synchronized fun acceptIndependentResult(
        context: Context,
        packet: IndependentlyAuthenticatedPokerFrame,
        effect: PostActionReceipt
    ): AuthorizedCycleStatus {
        val gate = resultFrameGate ?: return update(
            AuthorizedCycleStage.NOT_CONFIGURED, "Нет аутентифицированного источника")
        if (CaptureObservationMode.isSpectator() ||
            OperatorStopStore.isStopped(context) || !FrameStore.isCapturing)
            return update(AuthorizedCycleStage.STOPPED, "STOP или нет захвата")
        val command = OnDeviceLiveActionControl.guard.pendingCommandForEffect()
            ?: return update(AuthorizedCycleStage.RESULT_REJECTED,
                "Команда ещё не передана Android или была отменена")
        val frame = FrameStore.latest ?: return update(
            AuthorizedCycleStage.RESULT_REJECTED, "Нет нового кадра после команды")
        val accepted = gate.prepare(frame, packet, effect, command,
            activeTableId.orEmpty(), activeTurnId.orEmpty(),
            SystemClock.elapsedRealtime(), false)
        val post = accepted.frame ?: return update(
            AuthorizedCycleStage.RESULT_REJECTED, accepted.reason?.name ?: "Недостаточно данных")
        val evidence = OnDeviceEffectAuthority.inspect(command, effect,
            SystemClock.elapsedRealtime(), stopped = false)
        if (evidence.status == EffectGateStatus.REJECTED)
            return update(AuthorizedCycleStage.RESULT_REJECTED,
                "Источник не подтвердил причинный результат действия")
        if (evidence.status == EffectGateStatus.COLLECTING)
            return update(AuthorizedCycleStage.AWAITING_INDEPENDENT_RESULT,
                "Первое подтверждение принято, ожидается второе независимое")
        val ticket = evidence.ticket ?: return update(
            AuthorizedCycleStage.RESULT_REJECTED, "Нет одноразового билета результата")
        val withTicket = post.observation.copy(
            view = post.observation.view.copy(verifiedEffectTicket = ticket))
        val completed = OnDeviceAutonomousRuntime.acceptVerifiedObservation(
            context, withTicket, post.sourceProof)
        val success = completed.state == AutoLoopState.CONFIRMED
        return update(
            if (success) AuthorizedCycleStage.RESULT_CONFIRMED
            else AuthorizedCycleStage.RESULT_REJECTED,
            completed.explanation, completed.state)
    }

    @Synchronized fun clear(reason: String = "STOP") {
        activeTableId = null
        activeTurnId = null
        OnDeviceEffectAuthority.clear()
        joiner = null
        resultFrameGate = null
        sourceVerifier = null
        last = AuthorizedCycleStatus(AuthorizedCycleStage.STOPPED, reason)
    }

    private fun update(stage: AuthorizedCycleStage, description: String,
                       state: AutoLoopState? = null): AuthorizedCycleStatus {
        last = AuthorizedCycleStatus(stage, description, state)
        return last
    }
}
