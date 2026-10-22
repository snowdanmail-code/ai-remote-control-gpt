package com.example.airemote

/**
 * Explicit interface for an AUTHORIZED, independently authenticated source of
 * virtual-chip game state. The screenshot/ML Kit/ChatGPT login MUST NOT be used
 * as this provider. A real provider supplies its own proof verifier.
 *
 * This module cannot create proof IDs, table IDs, hand IDs or own-turn IDs.
 * No gestures, network calls, credentials or APK permissions here.
 */
data class IndependentlyAuthenticatedPokerFrame(
    val attestation: ExternalTurnAttestation,
    val stage: HandStage,
    val ownTurn: Boolean,
    val legalActions: Set<LiveAction>,
    val potHundredths: Long?,
    val callHundredths: Long?,
    val timerRemainingMs: Long?,
    val ownHoleCards: List<String>,
    val publicCards: List<String>,
    val stateFingerprint: String,
    val selectedBetHundredths: Long? = null,
    val minBetHundredths: Long? = null,
    val maxBetHundredths: Long? = null,
    val equity: FrameBoundEquity? = null,
    /** Count verified by authorized provider; unknown does NOT mean one. */
    val verifiedActiveOpponentCount: Int? = null
)

data class VerifiedPlayerJoinResult(
    val observation: AutonomousObservation?,
    val sourceProof: ExternalTurnAttestation?,
    val error: String
) {
    val accepted: Boolean get() = observation != null && sourceProof != null
}

/**
 * Frame + cards + measured buttons + independently signed state must describe
 * ONE frame. The source verification callback must validate an authentic
 * provider credential; never pass { true } in production.
 */
class AuthenticatedPlayerFrameJoiner(
    private val verifyExternalProof: ExternalAttestationVerifier,
    private val maximumAgeMs: Long = 1_200L
) {
    fun combine(
        frame: FrameInfo,
        cards: StableCardReading?,
        controlStrip: ActionStripReport?,
        authenticated: IndependentlyAuthenticatedPokerFrame,
        viewport: CaptureViewport,
        nowMs: Long,
        operatorStopped: Boolean
    ): VerifiedPlayerJoinResult {
        fun refuse(reason: String) = VerifiedPlayerJoinResult(null, null, reason)
        val p = authenticated.attestation
        if (operatorStopped || nowMs < frame.capturedAtMs ||
            nowMs - frame.capturedAtMs > maximumAgeMs ||
            p.capturedAtMs != frame.capturedAtMs ||
            p.frameSequence != frame.seq ||
            p.epoch != frame.captureEpoch ||
            p.windowGeneration != frame.windowGeneration ||
            p.packageName != frame.sourcePackage ||
            p.expiresAtMs <= nowMs || !verifyExternalProof.verify(p))
            return refuse("Источник, срок действия или происхождение кадра не подтверждены")
        if (p.tableId.isBlank() || p.handId.isBlank() || p.turnId.isBlank() ||
            p.providerId.isBlank() || p.proofId.isBlank() ||
            authenticated.stateFingerprint.isBlank())
            return refuse("Отсутствуют независимо выданные идентификаторы игры")
        if (cards == null || cards.captureEpoch != frame.captureEpoch ||
            cards.capturedAtMs > frame.capturedAtMs ||
            frame.capturedAtMs - cards.capturedAtMs > 1_400L ||
            cards.minimumSimilarity < .78 || cards.hole.size != 2 ||
            cards.community.size !in listOf(0, 3, 4, 5) ||
            cards.hole != authenticated.ownHoleCards ||
            cards.community != authenticated.publicCards ||
            (cards.hole + cards.community).toSet().size !=
                cards.hole.size + cards.community.size)
            return refuse("Карты кадра не совпадают с подтверждённым состоянием")
        val expectedStage = when (cards.community.size) {
            0 -> HandStage.PREFLOP
            3 -> HandStage.FLOP
            4 -> HandStage.TURN
            else -> HandStage.RIVER
        }
        if (authenticated.stage != expectedStage || !authenticated.ownTurn ||
            authenticated.timerRemainingMs == null || authenticated.timerRemainingMs < 1_600L ||
            authenticated.potHundredths == null || authenticated.potHundredths < 0 ||
            authenticated.callHundredths == null || authenticated.callHundredths < 0 ||
            authenticated.legalActions.isEmpty() ||
            authenticated.legalActions.size > 3 || !viewport.valid() ||
            viewport.jpegWidth != frame.width || viewport.jpegHeight != frame.height)
            return refuse("Стадия, ход, суммы или область экрана не подтверждены")
        if (controlStrip == null || controlStrip.status != ActionStripStatus.STABLE_DIAGNOSTIC ||
            controlStrip.frameSequence != frame.seq ||
            controlStrip.stable.size < 2)
            return refuse("Нет двух подтверждённых кадров кнопок")
        val calibrated = controlStrip.stable.mapNotNull { cue ->
            val tap = VerifiedScreenProjection.project(cue, viewport,
                p.packageName, p.windowGeneration)
            tap?.exactAction?.let { it to tap }
        }
        if (calibrated.size != controlStrip.stable.size ||
            calibrated.map { it.first }.toSet().size != calibrated.size ||
            !authenticated.legalActions.all { act -> calibrated.any { it.first == act } } ||
            calibrated.any { it.first !in authenticated.legalActions })
            return refuse("Надписи, доступные действия и реальные области кнопок расходятся")
        val taps = calibrated.toMap()
        val selected = authenticated.selectedBetHundredths
        val min = authenticated.minBetHundredths
        val max = authenticated.maxBetHundredths
        if (authenticated.legalActions.any { it == LiveAction.RAISE || it == LiveAction.BET } &&
            (min == null || max == null || selected == null || min <= 0 ||
                max < min || selected !in min..max))
            return refuse("Диапазон или выбранный размер BET/RAISE не подтверждены")
        if (authenticated.legalActions.contains(LiveAction.CHECK) &&
            authenticated.callHundredths != 0L)
            return refuse("CHECK противоречит положительной стоимости хода")
        if (authenticated.legalActions.contains(LiveAction.CALL) &&
            authenticated.callHundredths <= 0L)
            return refuse("CALL без подтверждённой суммы")
        val view = VerifiedLiveView(frame.captureEpoch, frame.seq, frame.capturedAtMs,
            p.handId, authenticated.stateFingerprint, true, true, true, true,
            authenticated.legalActions)
        val sourceEquity = authenticated.equity?.takeIf {
            it.externallyVerified && it.handId == p.handId && it.turnId == p.turnId &&
                it.captureEpoch == frame.captureEpoch && it.frameSequence == frame.seq
        }
        // If the authenticated provider also proves the number of active
        // opponents, a uniform-unknown baseline can be calculated locally.
        // It is NOT an opponent-range read or a guaranteed win probability.
        // Without this verified count, no paid action gets an equity estimate.
        val modelCount = authenticated.verifiedActiveOpponentCount
        val modeledEquity = if (sourceEquity == null && modelCount != null &&
            modelCount in 1..5) try {
            val sampled = UniformUnknownPokerEquity.compute(
                cards.hole.map(PokerCard::parse),
                cards.community.map(PokerCard::parse),
                modelCount, samples = 300,
                seed = (p.handId + ":" + p.turnId).hashCode())
            FrameBoundEquity(p.handId, p.turnId, frame.captureEpoch,
                frame.seq, sampled.potShareEquity, sampled.runouts,
                externallyVerified = true, exactEnumeration = false,
                provenance = "UNIFORM_UNKNOWN_CARDS_SIGNED_SOURCE")
        } catch (_: IllegalArgumentException) { null }
          catch (_: IllegalStateException) { null }
        else null
        val boundEquity = sourceEquity ?: modeledEquity
        val obs = AutonomousObservation(view, p.tableId, p.handId, p.turnId,
            p.packageName, p.windowGeneration, true, true,
            authenticated.stage, authenticated.timerRemainingMs,
            authenticated.potHundredths, authenticated.callHundredths,
            taps, selected, boundEquity, min, max)
        return VerifiedPlayerJoinResult(obs, p, "Согласованы независимое состояние, карты и кнопки")
    }
}
