package com.example.airemote

/** Offline model-proposal admission tests: no game app, no taps, no money. */
object PlayerModelDecisionScenarios {
    @JvmStatic fun main(args: Array<String>) {
        var passed = 0
        fun ok(value: Boolean, title: String) { check(value) { title }; passed++ }
        val pkg = "test.virtual.game"
        val view = VerifiedLiveView(7L, 33L, 1000L, "hand-a", "fresh", true,
            true, true, true, setOf(LiveAction.CALL, LiveAction.FOLD, LiveAction.RAISE))
        fun tap(action: LiveAction) = VerifiedTapArea(400, 1980, 600, 2070,
            500, 2025, 1080, 2340, action, action.name, pkg, 5)
        val obs = AutonomousObservation(view, "table-a", "hand-a", "turn-a",
            pkg, 5L, true, true, HandStage.TURN, 3000L, 120L, 20L,
            mapOf(LiveAction.CALL to tap(LiveAction.CALL),
                LiveAction.FOLD to tap(LiveAction.FOLD),
                LiveAction.RAISE to tap(LiveAction.RAISE)),
            60L, null, 40L, 100L)
        fun p(action: LiveAction = LiveAction.CALL, amount: Long? = null) =
            PlayerModelProposal(action, "hand-a", "turn-a", 7, 33, 5, 1050, 1180, amount)
        val guard = PlayerModelDecisionGuard
        val yes = guard.inspect(obs, p(), 1100)
        ok(yes.status == PlayerProposalStatus.ACCEPTABLE_CANDIDATE, "fresh legal call")
        ok(yes.candidate?.action == LiveAction.CALL && !yes.authorizesGesture,
            "candidate cannot authorize tap")
        ok(guard.inspect(obs, p().copy(handId="other"), 1100).status ==
            PlayerProposalStatus.WRONG_HAND_OR_TURN, "hand mismatch")
        ok(guard.inspect(obs, p().copy(frameSequence=32), 1100).status ==
            PlayerProposalStatus.WRONG_HAND_OR_TURN, "frame mismatch")
        ok(guard.inspect(obs, p().copy(turnId="other"), 1100).status ==
            PlayerProposalStatus.WRONG_HAND_OR_TURN, "turn mismatch")
        ok(guard.inspect(obs, p(), 1200).status ==
            PlayerProposalStatus.STALE_OR_FUTURE_RESPONSE, "expired")
        ok(guard.inspect(obs, p().copy(proposedAtMs=1110), 1100).status ==
            PlayerProposalStatus.STALE_OR_FUTURE_RESPONSE, "future response")
        ok(guard.inspect(obs, p(LiveAction.CHECK), 1100).status ==
            PlayerProposalStatus.NOT_LEGAL, "not legal")
        ok(guard.inspect(obs, p(LiveAction.RAISE, 120), 1100).status ==
            PlayerProposalStatus.UNSAFE_AMOUNT, "too high raise")
        ok(guard.inspect(obs, p(LiveAction.RAISE, 60), 1100).status ==
            PlayerProposalStatus.ACCEPTABLE_CANDIDATE, "exact already selected size")
        ok(guard.inspect(obs.copy(tableHandIndependentlyVerified=false), p(), 1100).status ==
            PlayerProposalStatus.UNVERIFIED_SOURCE, "no attestation")
        ok(guard.inspect(obs.copy(availableTaps=emptyMap()), p(), 1100).status ==
            PlayerProposalStatus.BUTTON_UNVERIFIED, "no button")
        ok(guard.inspect(obs, p().copy(amountChips=1L), 1100).status ==
            PlayerProposalStatus.UNSAFE_AMOUNT, "no call amount suggestion")
        println("PlayerModelDecisionScenarios: $passed/$passed passed")
    }
}
