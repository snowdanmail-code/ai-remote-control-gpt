package com.example.airemote

private var checked = 0
private fun checkCase(value: Boolean, label: String) { check(value) { label }; checked++ }
private fun start(epoch: Long = 5, window: Long = 3, hand: String = "H1") =
    VerifiedOpponentHandStart("T1", hand, epoch, window, 10, 11, 1000, 1150, 1180, true, true)
private fun act(key: String = "a1", player: String = "P1", street: String = "PREFLOP",
    action: String = "CALL", first: Long = 12, confirmed: Long = 13,
    t: Long = 1300, facing: Boolean? = true, identity: Boolean = true,
    hand: String = "H1", epoch: Long = 5, window: Long = 3) =
    ConfirmedOpponentAction("T1", hand, epoch, window, player, identity,
        key, street, action, facing, first, confirmed, t, t + 150, t + 170, true, true)
private fun receipt(first: Long = 10, terminal: Long = 30) =
    VerifiedFinishedHand("T1", "H1", 5, first, terminal, 5000, true, true, emptyList())

object OpponentAwarenessScenarios {
    @JvmStatic fun main(args: Array<String>) {
        val a = LiveOpponentAwareness()
        checkCase(a.observe(act()) == OpponentEvidenceResult.REJECTED, "no start")
        checkCase(a.begin(start()) == OpponentEvidenceResult.ACCEPTED, "verified start")
        checkCase(a.observe(act()) == OpponentEvidenceResult.ACCEPTED, "call")
        checkCase(a.observe(act()) == OpponentEvidenceResult.DUPLICATE, "dedup")
        checkCase(a.snapshot().single().verifiedActions == 1, "not double counted")
        checkCase(a.snapshot().single().voluntarilyEnteredPreflop == true, "VPIP true")
        checkCase(a.snapshot().single().preflopRaised == null, "PFR unknown not false")
        checkCase(a.prepareForVerifiedTerminal(receipt(), 3)?.opponents?.single()?.vpip == true, "transfer to receipt")
        checkCase(a.prepareForVerifiedTerminal(receipt(), 4) == null, "different window")
        checkCase(a.prepareForVerifiedTerminal(receipt(first = 11), 3) == null, "different first frame")
        checkCase(a.prepareForVerifiedTerminal(receipt(terminal = 13), 3) == null, "terminal must be later")
        checkCase(a.prepareForVerifiedTerminal(receipt().copy(terminalConfirmedByIndependentFrames = false), 3) == null, "no final evidence")
        val prior = a.contextForDecision { "past:${it.joinToString()}" }
        checkCase(prior.contains("past:P1"), "past history for stable key")
        val collisions = LiveOpponentAwareness()
        collisions.begin(start())
        checkCase(collisions.observe(act()) == OpponentEvidenceResult.ACCEPTED, "first")
        checkCase(collisions.observe(act(action = "RAISE")) == OpponentEvidenceResult.INVALIDATED, "conflict invalidates")
        checkCase(collisions.snapshot().isEmpty(), "conflict clears")
        val crossed = LiveOpponentAwareness()
        crossed.begin(start())
        checkCase(crossed.observe(act(hand = "H2")) == OpponentEvidenceResult.INVALIDATED, "new hand invalidates")
        checkCase(crossed.observe(act()) == OpponentEvidenceResult.REJECTED, "no restart after invalidation")
        val window = LiveOpponentAwareness()
        window.begin(start())
        checkCase(window.observe(act(window = 4)) == OpponentEvidenceResult.INVALIDATED, "window mismatch")
        val epoch = LiveOpponentAwareness()
        epoch.begin(start())
        checkCase(epoch.observe(act(epoch = 6)) == OpponentEvidenceResult.INVALIDATED, "epoch mismatch")
        val stale = LiveOpponentAwareness()
        stale.begin(start())
        checkCase(stale.observe(act().copy(inspectedAtMs = 3000)) == OpponentEvidenceResult.REJECTED, "stale")
        checkCase(stale.observe(act().copy(sourceVerified = false)) == OpponentEvidenceResult.INVALIDATED, "lost source proof invalidates")
        checkCase(stale.snapshot().isEmpty(), "lost source proof clears")
        stale.begin(start())
        checkCase(stale.observe(act().copy(actionConfirmedByIndependentFrames = false)) == OpponentEvidenceResult.INVALIDATED, "one frame invalidates")
        stale.begin(start())
        checkCase(stale.observe(act().copy(confirmedFrame = 12)) == OpponentEvidenceResult.REJECTED, "same frame")
        checkCase(stale.observe(act().copy(confirmedCapturedAtMs = 1320)) == OpponentEvidenceResult.REJECTED, "too short confirmation")
        checkCase(stale.observe(act().copy(street = "FLOP", action = "BET", facingBetVerified = true)) == OpponentEvidenceResult.REJECTED, "bet facing bet")
        checkCase(stale.observe(act().copy(action = "CHECK", facingBetVerified = true)) == OpponentEvidenceResult.REJECTED, "check facing bet")
        checkCase(stale.observe(act().copy(action = "BET")) == OpponentEvidenceResult.REJECTED, "preflop bet")
        checkCase(stale.observe(act().copy(action = "CALL", facingBetVerified = false)) == OpponentEvidenceResult.REJECTED, "call no bet")
        checkCase(stale.observe(act().copy(actionKey = "\u0000")) == OpponentEvidenceResult.REJECTED, "invalid key")
        checkCase(stale.observe(act().copy(firstFrame = 10)) == OpponentEvidenceResult.REJECTED, "pre-start frame")
        checkCase(stale.observe(act()) == OpponentEvidenceResult.ACCEPTED, "valid after rejected event")
        checkCase(stale.observe(act(key = "a2", first = 12, confirmed = 13, t = 1700)) == OpponentEvidenceResult.REJECTED, "not later frame")
        checkCase(stale.observe(act(key = "a3", first = 13, confirmed = 14, t = 1600)) == OpponentEvidenceResult.REJECTED, "overlap previous confirmation")
        val anonymous = LiveOpponentAwareness()
        anonymous.begin(start())
        anonymous.observe(act(identity = false, player = "seat2"))
        checkCase(anonymous.contextForDecision { "historical:${it.size}" }.contains("historical:0"), "no long-term anonymous lookup")
        checkCase(anonymous.prepareForVerifiedTerminal(receipt(), 3)?.opponents?.single()?.identityVerified == false, "anonymous never persisted")
        checkCase(anonymous.observe(act(key = "a2", player = "seat2", identity = true, first = 14, confirmed = 15, t = 1600)) == OpponentEvidenceResult.INVALIDATED, "identity contradiction")
        checkCase(anonymous.snapshot().isEmpty(), "identity reset")
        val raises = LiveOpponentAwareness()
        raises.begin(start())
        raises.observe(act(action = "RAISE"))
        raises.observe(act(key = "b1", street = "FLOP", action = "BET", first = 14, confirmed = 15, t = 1600, facing = false))
        raises.observe(act(key = "c1", street = "TURN", action = "CALL", first = 16, confirmed = 17, t = 1900))
        raises.observe(act(key = "d1", street = "RIVER", action = "FOLD", first = 18, confirmed = 19, t = 2200))
        val stats = raises.snapshot().single()
        checkCase(stats.preflopRaised == true, "preflop raise")
        checkCase(stats.postflopBetsOrRaises == 1, "aggression")
        checkCase(stats.postflopCalls == 1, "calls")
        checkCase(stats.foldedFacingBet == true, "fold")
        val proposal = raises.prepareForVerifiedTerminal(receipt(), 3)!!.opponents.single()
        checkCase(proposal.postflopBetsOrRaises == 1 && proposal.postflopCalls == 1, "proposal counters")
        checkCase(proposal.foldedWhenFacingBet == true, "fold evidence")
        checkCase(proposal.showdown == null && proposal.showdownWon == null, "no invented showdown")
        raises.reset()
        checkCase(raises.snapshot().isEmpty(), "stop clears only transient")
        val concurrent = LiveOpponentAwareness()
        concurrent.begin(start())
        val outcomes = java.util.Collections.synchronizedList(mutableListOf<OpponentEvidenceResult>())
        val workers = (1..16).map { Thread { outcomes.add(concurrent.observe(act())) } }
        workers.forEach { it.start() }; workers.forEach { it.join() }
        checkCase(outcomes.count { it == OpponentEvidenceResult.ACCEPTED } == 1, "one concurrent accepted")
        checkCase(outcomes.count { it == OpponentEvidenceResult.DUPLICATE } == 15, "rest concurrent duplicates")
        checkCase(concurrent.snapshot().single().verifiedActions == 1, "concurrent not double counted")
        val invalidStart = LiveOpponentAwareness()
        checkCase(invalidStart.begin(start().copy(stableTableAndHandVerified = false)) == OpponentEvidenceResult.REJECTED, "bad start")
        checkCase(invalidStart.begin(start().copy(sourceVerified = false)) == OpponentEvidenceResult.REJECTED, "unverified source")
        checkCase(invalidStart.begin(start().copy(secondCapturedAtMs = 1050)) == OpponentEvidenceResult.REJECTED, "same time")
        println("Opponent awareness: $checked checks passed")
    }
}

/** Verified boundary integration exercises TWO terminal frames, never one. */
object OpponentGateIntegrationScenarios {
    @JvmStatic fun main(args: Array<String>) {
        var checks = 0
        fun test(v: Boolean, msg: String) { check(v) { msg }; checks++ }
        val collector = LiveOpponentAwareness()
        test(collector.begin(start()) == OpponentEvidenceResult.ACCEPTED, "collector starts")
        test(collector.observe(act()) == OpponentEvidenceResult.ACCEPTED, "observed preflop")
        val saved = mutableListOf<VerifiedFinishedHand>()
        val gate = VerifiedHandCompletionGate({ h -> saved.add(h); HandLearningResult.APPLIED })
        fun boundary(frame: Long, ms: Long, phase: HandBoundaryPhase, terminal: Boolean = false,
            window: Long = 3, hand: String = "H1") = VerifiedHandBoundaryFrame(
            tableKey = "T1", handKey = hand, captureEpoch = 5,
            frameSequence = frame, capturedAtElapsedMs = ms,
            inspectedAtElapsedMs = ms + 25,
            sourceVerified = true, stableHandKeyVerified = true,
            recognitionConfidence = 0.99, phase = phase,
            terminalCueVerified = terminal,
            terminalCueKey = if (terminal) "settlement-verified" else null,
            windowGeneration = window
        )
        test(gate.ingest(boundary(10, 1000, HandBoundaryPhase.ACTIVE)).status == HandBoundaryStatus.WAITING_FOR_ACTIVE, "first active")
        test(gate.ingest(boundary(11, 1150, HandBoundaryPhase.ACTIVE)).status == HandBoundaryStatus.ACTIVE_CONFIRMED, "second active")
        val firstTerminal = boundary(30, 5000, HandBoundaryPhase.TERMINAL, true)
        test(collector.proposalFromTrustedTerminal(firstTerminal)!!.opponents.single().vpip == true, "proposal exists")
        test(gate.ingest(firstTerminal, collector.proposalFromTrustedTerminal(firstTerminal)).status == HandBoundaryStatus.WAITING_FOR_TERMINAL, "one terminal is insufficient")
        test(saved.isEmpty(), "nothing saved yet")
        val secondTerminal = boundary(31, 5250, HandBoundaryPhase.TERMINAL, true)
        test(gate.ingest(secondTerminal, collector.proposalFromTrustedTerminal(secondTerminal)).status == HandBoundaryStatus.COMMITTED, "two terminal confirms")
        test(saved.size == 1 && saved[0].opponents.single().vpip == true, "facts committed once")
        test(gate.ingest(boundary(32, 5450, HandBoundaryPhase.TERMINAL, true),
            collector.proposalFromTrustedTerminal(boundary(32, 5450, HandBoundaryPhase.TERMINAL, true))).status == HandBoundaryStatus.ALREADY_COMMITTED,
            "repeat terminal ignored")
        test(saved.size == 1, "still one commit")
        test(collector.proposalFromTrustedTerminal(boundary(33, 5700, HandBoundaryPhase.TERMINAL, true, window = 4)) == null, "new window cannot inherit")
        test(collector.proposalFromTrustedTerminal(boundary(33, 5700, HandBoundaryPhase.TERMINAL, true, hand = "H2")) == null, "new hand cannot inherit")
        test(collector.proposalFromTrustedTerminal(boundary(33, 5700, HandBoundaryPhase.TERMINAL, false)) == null, "no positive cue")
        collector.reset()
        test(collector.proposalFromTrustedTerminal(secondTerminal) == null, "stop resets")
        println("Opponent completion integration: $checks checks passed")
    }
}
