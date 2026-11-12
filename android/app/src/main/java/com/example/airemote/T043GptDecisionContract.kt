package com.example.airemote

import org.json.JSONArray
import org.json.JSONObject

/**
 * The ChatGPT boundary for an independently verified live turn.
 *
 * The model is NOT an authority for hand/turn identity, gesture geometry,
 * amounts already selected in the UI, card provenance or permission to play.
 * Local verified evidence supplies every binding field. No model text can
 * authorize an Android gesture.
 */
object T043GptDecisionContract {
    const val SCHEMA = "t043.gpt.decision.v1"
    const val MAX_RESPONSE_CHARS = 1024
    const val MAX_FRAME_AGE_MS = 1_000L

    /** Null means there is no sufficiently current, independently verified game state. */
    fun buildPrompt(
        observation: AutonomousObservation,
        cards: StableCardReading,
        nowMs: Long,
        verifiedOpponentProfiles: List<OpponentLearningSummary> = emptyList()
    ): String? {
        val view = observation.view
        if (observation.tableId.isBlank() || observation.handId.isBlank() ||
            observation.turnId.isBlank() || observation.phase == HandStage.UNKNOWN ||
            !observation.tableHandIndependentlyVerified ||
            !observation.windowIndependentlyVerified || !view.foregroundVerified ||
            !view.stageVerified || !view.turnVerified || !view.exactLabelsVerified ||
            view.handKey != observation.handId ||
            view.legalActions.isEmpty() ||
            nowMs < view.capturedAtMs || nowMs - view.capturedAtMs > MAX_FRAME_AGE_MS ||
            cards.captureEpoch != view.captureEpoch ||
            cards.capturedAtMs > view.capturedAtMs ||
            view.capturedAtMs - cards.capturedAtMs > 1_400L ||
            cards.hole.size != 2 || cards.community.size !in setOf(0, 3, 4, 5))
            return null
        val expectedStreet = when (cards.community.size) {
            0 -> HandStage.PREFLOP
            3 -> HandStage.FLOP
            4 -> HandStage.TURN
            5 -> HandStage.RIVER
            else -> HandStage.UNKNOWN
        }
        if (expectedStreet != observation.phase) return null
        val normalizedCards = try {
            (cards.hole + cards.community).map { PokerCard.parse(it).toString() }
        } catch (_: IllegalArgumentException) { return null }
        if (normalizedCards.toSet().size != normalizedCards.size) return null

        val packet = JSONObject()
            .put("schema", SCHEMA)
            .put("mode", "RECOMMENDATION_ONLY")
            .put("hand_id", observation.handId)
            .put("turn_id", observation.turnId)
            .put("capture_epoch", view.captureEpoch)
            .put("frame_sequence", view.frameSequence)
            .put("street", observation.phase.name)
            .put("hole_cards", JSONArray(normalizedCards.take(2)))
            .put("board_cards", JSONArray(normalizedCards.drop(2)))
            .put("legal_actions", JSONArray(view.legalActions.map { it.name }.sorted()))
            .put("pot_chips", observation.potChips ?: JSONObject.NULL)
            .put("call_chips", observation.callChips ?: JSONObject.NULL)
            .put("timer_ms", observation.timerRemainingMs ?: JSONObject.NULL)
            .put("min_bet_chips", observation.minVerifiedBetChips ?: JSONObject.NULL)
            .put("max_bet_chips", observation.maxVerifiedBetChips ?: JSONObject.NULL)
            .put("selected_bet_chips", observation.selectedBetChips ?: JSONObject.NULL)
        // Prior verified-hand profiles only. Names/IDs are deliberately omitted
        // from the model prompt; counters have explicit opportunity denominators.
        val profiles = JSONArray()
        verifiedOpponentProfiles.take(3).forEachIndexed { index, p ->
            if (p.observedHands >= 0 && p.vpipOpportunities >= 0 &&
                p.pfrOpportunities >= 0 && p.facedBetOpportunities >= 0 &&
                p.vpipSeen in 0..p.vpipOpportunities &&
                p.pfrSeen in 0..p.pfrOpportunities &&
                p.foldsFacingBet in 0..p.facedBetOpportunities) {
                profiles.put(JSONObject()
                    .put("ref", "opponent_${index + 1}")
                    .put("observed_hands", p.observedHands)
                    .put("vpip_seen", p.vpipSeen)
                    .put("vpip_opportunities", p.vpipOpportunities)
                    .put("pfr_seen", p.pfrSeen)
                    .put("pfr_opportunities", p.pfrOpportunities)
                    .put("postflop_aggression_actions", p.aggressionActions.coerceAtLeast(0))
                    .put("postflop_calls", p.postflopCalls.coerceAtLeast(0))
                    .put("folds_facing_bet", p.foldsFacingBet)
                    .put("faced_bet_opportunities", p.facedBetOpportunities)
                    .put("showdowns_seen", p.showdownsSeen.coerceAtLeast(0))
                    .put("showdowns_won", p.showdownsWon.coerceAtLeast(0)))
            }
        }
        packet.put("verified_opponent_statistics", profiles)
        // JSON-escape every external field. No free-text player names or OCR
        // instructions are interpolated into the system directive.
        val directive = "Analyze this Texas Hold'em turn with virtual chips. " +
            "Unknown values are unknown; do not invent cards or opponent stats. " +
            "Player statistics have limited samples. " +
            "Return ONLY a JSON object with keys schema, action, amount_chips. " +
            "schema must be $SCHEMA; action must be one of legal_actions. " +
            "For BET/RAISE use the already selected and verified amount, " +
            "otherwise amount_chips must be null. " +
            "This is advice, not permission to tap.\\n"
        var prompt = directive + packet.toString()
        while (prompt.length > 1_600 && profiles.length() > 0) {
            profiles.remove(profiles.length() - 1)
            prompt = directive + packet.toString()
        }
        return prompt.takeIf { it.length <= 1600 }
    }

    data class Review(
        val accepted: Boolean,
        val reason: String,
        val proposal: PlayerModelProposal? = null
    )

    /**
     * Bind a model recommendation to the LOCAL observation, never to echoed
     * identifiers or timestamps from model output. The existing independent
     * decision and dispatch gates remain mandatory.
     */
    fun review(
        response: String,
        observation: AutonomousObservation,
        nowMs: Long
    ): Review {
        if (response.length !in 2..MAX_RESPONSE_CHARS ||
            response.firstOrNull() != '{' || response.lastOrNull() != '}')
            return Review(false, "INVALID_JSON_SHAPE")
        val json = try { JSONObject(response) }
            catch (_: Exception) { return Review(false, "INVALID_JSON") }
        val allowed = setOf("schema", "action", "amount_chips")
        val keys = json.keys().asSequence().toSet()
        if (keys != allowed || json.optString("schema") != SCHEMA)
            return Review(false, "INVALID_SCHEMA")
        val actionName = json.optString("action")
        // ALL_IN needs its own verified stack/slider path; never alias it to
        // a different button or silently guess an amount.
        val action = LiveAction.entries.firstOrNull { it.name == actionName }
            ?: return Review(false, "UNSUPPORTED_ACTION")
        val amount = if (json.isNull("amount_chips")) null else {
            val raw = json.opt("amount_chips")
            if (raw !is Int && raw !is Long)
                return Review(false, "INVALID_AMOUNT")
            (raw as Number).toLong().takeIf { it > 0L }
                ?: return Review(false, "INVALID_AMOUNT")
        }
        if (action in setOf(LiveAction.BET, LiveAction.RAISE) && amount == null ||
            action !in setOf(LiveAction.BET, LiveAction.RAISE) && amount != null)
            return Review(false, "AMOUNT_ACTION_MISMATCH")
        // A response that arrived after the authenticated frame is stale
        // regardless of the model's view of the remaining timer.
        val view = observation.view
        if (nowMs < view.capturedAtMs ||
            nowMs - view.capturedAtMs > MAX_FRAME_AGE_MS ||
            observation.handId.isBlank() || observation.turnId.isBlank())
            return Review(false, "STALE_FRAME")
        val proposal = PlayerModelProposal(action, observation.handId,
            observation.turnId, view.captureEpoch, view.frameSequence,
            observation.windowGeneration, nowMs, nowMs + 200L, amount)
        val outcome = PlayerModelDecisionGuard.inspect(observation, proposal, nowMs)
        return if (outcome.status == PlayerProposalStatus.ACCEPTABLE_CANDIDATE)
            Review(true, "ACCEPTABLE_CANDIDATE_NOT_A_GESTURE", proposal)
        else Review(false, outcome.status.name)
    }
}
