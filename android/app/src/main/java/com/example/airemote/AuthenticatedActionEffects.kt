package com.example.airemote

/** Two independently authenticated game-state observations after OS dispatch.
 * A changed JPEG fingerprint or gesture callback is insufficient.
 */
data class PostActionReceipt(
    val providerId: String, val proofId: String, val commandId: String,
    val handKey: String, val action: LiveAction, val epoch: Long,
    val windowGeneration: Long, val frameSequence: Long,
    val capturedAtMs: Long, val fingerprint: String,
    val heroTurnClosed: Boolean, val heroFolded: Boolean,
    val contributionBefore: Long, val contributionAfter: Long,
    val potBefore: Long, val potAfter: Long
)

fun interface IndependentEffectVerifier {
    fun verify(receipt: PostActionReceipt): Boolean
}

/** One-time, command-scoped token. Never created from OS gesture onCompleted alone. */
data class VerifiedEffectTicket(
    val commandId: String, val handKey: String, val action: LiveAction,
    val captureEpoch: Long, val afterFrame: Long, val fingerprint: String,
    val expiresAtMs: Long, val nonce: Long
)

enum class EffectGateStatus { REJECTED, COLLECTING, CONFIRMED_EFFECT }
data class EffectGateReport(val status: EffectGateStatus, val reason: String,
                            val ticket: VerifiedEffectTicket? = null)

class AuthenticatedActionEffects(
    private val provider: IndependentEffectVerifier,
    private val maxAgeMs: Long = 1500L,
    private val maxGapMs: Long = 2200L
) {
    private var previous: PostActionReceipt? = null
    private var activeCommand: LiveCommand? = null
    private val issued = mutableMapOf<Long, VerifiedEffectTicket>()
    private var serial = 0L

    @Synchronized fun reset() {
        previous = null; activeCommand = null; issued.clear()
    }
    private fun causalEffect(proof: PostActionReceipt): Boolean {
        if (!proof.heroTurnClosed || proof.contributionBefore < 0 ||
            proof.contributionAfter < 0 || proof.potBefore < 0 || proof.potAfter < 0 ||
            proof.contributionAfter < proof.contributionBefore || proof.potAfter < proof.potBefore) return false
        val delta = proof.contributionAfter - proof.contributionBefore
        val potDelta = proof.potAfter - proof.potBefore
        return when (proof.action) {
            LiveAction.FOLD -> proof.heroFolded && delta == 0L
            LiveAction.CHECK -> !proof.heroFolded && delta == 0L
            LiveAction.CALL, LiveAction.BET, LiveAction.RAISE ->
                !proof.heroFolded && delta > 0L && potDelta >= delta
        }
    }
    @Synchronized fun inspect(command: LiveCommand, proof: PostActionReceipt?,
        nowMs: Long, stopped: Boolean): EffectGateReport {
        fun reject(why: String): EffectGateReport {
            reset(); return EffectGateReport(EffectGateStatus.REJECTED, why)
        }
        if (stopped || proof == null || !provider.verify(proof) ||
            proof.providerId.isBlank() || proof.proofId.isBlank() || proof.fingerprint.isBlank() ||
            proof.handKey != command.handKey || proof.commandId != command.id ||
            proof.action != command.action || proof.epoch != command.captureEpoch ||
            proof.windowGeneration < 0 || proof.frameSequence <= command.basedOnFrame ||
            proof.capturedAtMs <= command.sourceCapturedAtMs || proof.capturedAtMs <= command.createdMs || proof.capturedAtMs > nowMs ||
            nowMs - proof.capturedAtMs > maxAgeMs || !causalEffect(proof))
            return reject("No verified causal post-action state")
        if (activeCommand != null && activeCommand != command)
            return reject("Command changed while awaiting confirmation")
        val old = previous
        activeCommand = command
        if (old == null) {
            previous = proof
            return EffectGateReport(EffectGateStatus.COLLECTING, "Await second independently authenticated frame")
        }
        if (old.providerId != proof.providerId || old.proofId == proof.proofId ||
            old.commandId != proof.commandId || old.handKey != proof.handKey ||
            old.epoch != proof.epoch || old.windowGeneration != proof.windowGeneration ||
            old.action != proof.action || old.frameSequence >= proof.frameSequence ||
            old.capturedAtMs >= proof.capturedAtMs || proof.capturedAtMs - old.capturedAtMs > maxGapMs ||
            // Same settled causal facts, not an animation or second charge.
            old.contributionBefore != proof.contributionBefore ||
            old.contributionAfter != proof.contributionAfter ||
            old.potBefore != proof.potBefore || old.potAfter != proof.potAfter ||
            old.heroFolded != proof.heroFolded || !old.heroTurnClosed ||
            old.fingerprint != proof.fingerprint)
            return reject("Conflicting, duplicated or reordered post-action frames")
        serial++
        val ticket = VerifiedEffectTicket(command.id, command.handKey, command.action,
            command.captureEpoch, proof.frameSequence, proof.fingerprint,
            nowMs + maxAgeMs, serial)
        if (issued.size >= 32) issued.clear()
        issued[serial] = ticket
        previous = null; activeCommand = null
        return EffectGateReport(EffectGateStatus.CONFIRMED_EFFECT, "Causal state confirmed", ticket)
    }

    @Synchronized fun accept(ticket: VerifiedEffectTicket, command: LiveCommand,
                             view: VerifiedLiveView, nowMs: Long): Boolean {
        val known = issued[ticket.nonce] ?: return false
        if (known != ticket || ticket.commandId != command.id || ticket.handKey != command.handKey ||
            ticket.action != command.action || ticket.captureEpoch != command.captureEpoch ||
            ticket.afterFrame != view.frameSequence || ticket.fingerprint != view.stateFingerprint ||
            view.captureEpoch != ticket.captureEpoch || view.handKey != ticket.handKey ||
            ticket.expiresAtMs < nowMs) return false
        issued.remove(ticket.nonce)
        return true
    }
}

/** Not armed in production until an independent provider is configured. */
object OnDeviceEffectAuthority {
    @Volatile private var gate: AuthenticatedActionEffects? = null
    /** Real effect verification must be present BEFORE any automatic paid action.
     * STOP clears this authority; no invisible resurrection on the next ARM. */
    fun configured(): Boolean = gate != null
    @Synchronized fun install(verifier: IndependentEffectVerifier) { gate?.reset(); gate = AuthenticatedActionEffects(verifier) }
    @Synchronized fun clear() { gate?.reset(); gate = null }
    fun inspect(command: LiveCommand, receipt: PostActionReceipt?, nowMs: Long, stopped: Boolean): EffectGateReport =
        gate?.inspect(command, receipt, nowMs, stopped)
            ?: EffectGateReport(EffectGateStatus.REJECTED, "No authenticated effect provider")
    fun consume(ticket: VerifiedEffectTicket, command: LiveCommand, view: VerifiedLiveView, nowMs: Long): Boolean =
        gate?.accept(ticket, command, view, nowMs) == true
}
