package com.example.airemote

private class RecordingTransport : AutonomousTransport {
    val effects = AuthenticatedActionEffects(IndependentEffectVerifier { it.providerId == "test-independent" })
    val guard = LiveGameActionGuard(effectTicketVerifier = { token, c, v, ms -> effects.accept(token, c, v, ms) })
    var pendingForTest: LiveCommand? = null
    fun signedEffect(view: VerifiedLiveView, nowMs: Long): VerifiedLiveView {
        val c = pendingForTest ?: error("no dispatched command")
        fun receipt(seq: Long, at: Long, id: String) = PostActionReceipt(
            "test-independent", id, c.id, c.handKey, c.action, c.captureEpoch, 18,
            seq, at, view.stateFingerprint, true, c.action == LiveAction.FOLD,
            0, 0, 100, 100)
        check(view.frameSequence > c.basedOnFrame + 1)
        val first = effects.inspect(c, receipt(view.frameSequence-1, view.capturedAtMs-100, "first"), nowMs, false)
        check(first.status == EffectGateStatus.COLLECTING)
        val second = effects.inspect(c, receipt(view.frameSequence, view.capturedAtMs, "second"), nowMs, false)
        check(second.status == EffectGateStatus.CONFIRMED_EFFECT)
        return view.copy(verifiedEffectTicket = second.ticket)
    }
    var sent = 0
    var stops = 0
    init { check(guard.armByOperator()) }
    override fun submit(id: String, action: LiveAction, view: VerifiedLiveView,
                        tap: VerifiedTapArea, nowMs: Long): LiveCommandStatus {
        val state = guard.begin(id, action, view, nowMs, true)
        if (state == LiveCommandStatus.READY_TO_DISPATCH) {
            pendingForTest = LiveCommand(id, view.handKey, action, view.captureEpoch, view.frameSequence, view.stateFingerprint, nowMs, view.capturedAtMs)
            check(guard.claimDispatch(id)); sent++
        }
        return state
    }
    override fun observe(view: VerifiedLiveView, nowMs: Long) = guard.observe(view, nowMs)
    override fun stop() { stops++; guard.stop() }
}

fun main() {
    var n = 0
    fun test(name: String, body: () -> Unit) { body(); println("PASS $name"); n++ }
    val pkg = "org.example.verifiedpoker"
    val tap = VerifiedTapArea(350, 2030, 700, 2150, 525, 2090, 1080, 2340,
        LiveAction.CHECK, "CHECK", pkg, 18L)
    val v = VerifiedLiveView(2L, 14L, 1000L, "hand2", "fingerprint-a", true,
        true, true, true, setOf(LiveAction.CHECK))
    fun obs(view: VerifiedLiveView = v, turn: String = "turn1", table: String = "table7",
            verified: Boolean = true, remaining: Long? = 7000,
            taps: Map<LiveAction, VerifiedTapArea> = mapOf(LiveAction.CHECK to tap)) =
        AutonomousObservation(view, table, "hand2", turn, pkg, 18L,
            verified, verified, HandStage.PREFLOP, remaining, 100, 0, taps)
    fun setup(strategy: AutonomousStrategy = AutonomousStrategy {
        AutonomousDecision(LiveAction.CHECK, it.turnId, "CHECK when free")
    }): Pair<AutonomousActionLoop, RecordingTransport> {
        val transport = RecordingTransport()
        return AutonomousActionLoop(strategy, transport) to transport
    }
    test("explicit operator arm required") {
        val (loop, transport) = setup()
        check(loop.onVerifiedObservation(obs(), 1100, false, true, true).state == AutoLoopState.STOPPED)
        check(transport.sent == 0)
        check(!loop.arm(true, false)); check(!loop.arm(false, true))
        check(loop.arm(true, true)); check(!loop.arm(true, true))
    }
    test("verified turn dispatches exactly once") {
        val (loop, t) = setup(); check(loop.arm(true, true))
        check(loop.onVerifiedObservation(obs(), 1100, false, true, true).state == AutoLoopState.WAITING_FOR_RESULT)
        check(t.sent == 1)
        check(loop.onVerifiedObservation(obs(v.copy(frameSequence=15,capturedAtMs=1150)), 1170,
            false, true, true).state == AutoLoopState.WAITING_FOR_RESULT)
        check(t.sent == 1)
        val changed = v.copy(frameSequence=16, capturedAtMs=1250,
            stateFingerprint="fingerprint-b", actionEffectVerified=true, turnVerified=false)
        check(loop.onVerifiedObservation(obs(t.signedEffect(changed, 1260),turn="nextTurn",remaining=null),1260,
            false,true,true).state == AutoLoopState.CONFIRMED)
        check(loop.onVerifiedObservation(obs(changed.copy(turnVerified=true,exactLabelsVerified=true),
            turn="turn1"),1261,false,true,true).state == AutoLoopState.WAITING_FOR_DECISION)
        check(t.sent == 1)
    }
    test("never re-tap on timeout") {
        val (loop, t) = setup(); loop.arm(true,true)
        loop.onVerifiedObservation(obs(),1100,false,true,true)
        val later=v.copy(frameSequence=20,capturedAtMs=5080)
        check(loop.onVerifiedObservation(obs(later),5160,false,true,true).state == AutoLoopState.BLOCKED_UNCERTAIN)
        check(t.sent==1 && t.stops >= 1)
        check(loop.onVerifiedObservation(obs(later),5170,false,true,true).state==AutoLoopState.BLOCKED_UNCERTAIN)
    }
    test("loss of observation after dispatch halts") {
        val (loop,t)=setup();loop.arm(true,true)
        loop.onVerifiedObservation(obs(),1100,false,true,true)
        check(loop.onVerifiedObservation(null,1150,false,true,true).state==AutoLoopState.BLOCKED_UNCERTAIN)
        check(t.sent==1)
    }
    test("app window or table changed while pending blocks") {
        val (loop,t)=setup();loop.arm(true,true)
        loop.onVerifiedObservation(obs(),1100,false,true,true)
        check(loop.onVerifiedObservation(obs(view=v.copy(frameSequence=15,capturedAtMs=1150),table="other"),
            1160,false,true,true).state==AutoLoopState.BLOCKED_UNCERTAIN)
        check(t.sent==1)
    }
    test("missing independent proof prevents tap") {
        val (loop,t)=setup();loop.arm(true,true)
        check(loop.onVerifiedObservation(obs(verified=false),1100,false,true,true).state==AutoLoopState.WAITING_FOR_PROOF)
        check(t.sent==0)
    }
    test("stale frame prevents tap") {
        val (loop,t)=setup();loop.arm(true,true)
        check(loop.onVerifiedObservation(obs(),2500,false,true,true).state==AutoLoopState.WAITING_FOR_PROOF)
        check(t.sent==0)
    }
    test("timer too short prevents tap") {
        val (loop,t)=setup();loop.arm(true,true)
        check(loop.onVerifiedObservation(obs(remaining=1200),1100,false,true,true).state==AutoLoopState.WAITING_FOR_PROOF)
        check(t.sent==0)
    }
    test("unknown button position prevents tap") {
        val (loop,t)=setup();loop.arm(true,true)
        loop.onVerifiedObservation(obs(taps=emptyMap()),1100,false,true,true)
        check(t.sent==0)
    }
    test("strategy missing decision prevents tap") {
        val (loop,t)=setup(AutonomousStrategy {null});loop.arm(true,true)
        check(loop.onVerifiedObservation(obs(),1100,false,true,true).state==AutoLoopState.WAITING_FOR_DECISION)
        check(t.sent==0)
    }
    test("strategy decision bound to current turn") {
        val (loop,t)=setup(AutonomousStrategy {AutonomousDecision(LiveAction.CHECK,"old","not current")})
        loop.arm(true,true);loop.onVerifiedObservation(obs(),1100,false,true,true)
        check(t.sent==0)
    }
    test("no bet without verified amount") {
        val (loop,t)=setup(AutonomousStrategy {AutonomousDecision(LiveAction.BET,it.turnId,"bet",200)})
        loop.arm(true,true)
        loop.onVerifiedObservation(obs(view=v.copy(legalActions=setOf(LiveAction.BET)),
            taps=mapOf(LiveAction.BET to tap.copy(exactAction=LiveAction.BET,recognizedLabel="BET"))),
            1100,false,true,true)
        check(t.sent==0)
    }
    test("explicit STOP disables ongoing cycle") {
        val (loop,t)=setup();loop.arm(true,true)
        loop.onVerifiedObservation(obs(),1100,false,true,true)
        check(loop.onVerifiedObservation(obs(),1150,true,true,true).state==AutoLoopState.STOPPED)
        check(t.stops>0 && t.sent==1)
    }
    test("permission revoked disables ongoing cycle") {
        val (loop,t)=setup();loop.arm(true,true)
        loop.onVerifiedObservation(obs(),1100,false,true,true)
        check(loop.onVerifiedObservation(obs(),1150,false,true,false).state==AutoLoopState.STOPPED)
        check(t.sent==1)
    }
    test("capture lost disables ongoing cycle") {
        val (loop,t)=setup();loop.arm(true,true)
        loop.onVerifiedObservation(obs(),1100,false,true,true)
        check(loop.onVerifiedObservation(obs(),1150,false,false,true).state==AutoLoopState.STOPPED)
        check(t.sent==1)
    }
    test("invalid tap label or rectangle prevents tap") {
        val (loop,t)=setup();loop.arm(true,true)
        loop.onVerifiedObservation(obs(taps=mapOf(LiveAction.CHECK to tap.copy(recognizedLabel="CALL"))),
            1100,false,true,true)
        check(t.sent==0)
    }
    test("new verified turn may act after confirmed result") {
        val (loop,t)=setup();loop.arm(true,true)
        loop.onVerifiedObservation(obs(),1100,false,true,true)
        val changed=v.copy(frameSequence=16,capturedAtMs=1250,stateFingerprint="fingerprint-b",
            actionEffectVerified=true)
        check(loop.onVerifiedObservation(obs(t.signedEffect(changed, 1260),turn="turn2"),1260,false,true,true).state==AutoLoopState.CONFIRMED)
        check(loop.onVerifiedObservation(obs(changed.copy(frameSequence=17,capturedAtMs=1310),turn="turn2"),
            1320,false,true,true).state == AutoLoopState.WAITING_FOR_RESULT)
        check(t.sent==2)
    }
    test("strategy exception fails closed") {
        val (loop, t) = setup(AutonomousStrategy { throw IllegalStateException("simulated offline") })
        loop.arm(true, true)
        check(loop.onVerifiedObservation(obs(),1100,false,true,true).state == AutoLoopState.BLOCKED_UNCERTAIN)
        check(t.sent == 0 && t.stops > 0)
    }
    test("STOP is immediate while strategy waits") {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val (loop, t) = setup(AutonomousStrategy {
            entered.countDown()
            release.await(3, java.util.concurrent.TimeUnit.SECONDS)
            AutonomousDecision(LiveAction.CHECK, it.turnId, "valid")
        })
        check(loop.arm(true, true))
        val background = Thread { loop.onVerifiedObservation(obs(),1100,false,true,true) }
        background.start()
        check(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
        val began = System.nanoTime()
        loop.requestAbort()
        val elapsed = (System.nanoTime() - began) / 1_000_000L
        release.countDown()
        background.join(2000)
        check(!background.isAlive && elapsed < 500L && t.sent == 0)
        check(loop.status().state == AutoLoopState.STOPPED)
    }
    test("deadline after slow strategy prevents command") {
        var simulatedNow = 1100L
        val t = RecordingTransport()
        val loop = AutonomousActionLoop(AutonomousStrategy {
            simulatedNow = 2500L
            AutonomousDecision(LiveAction.CHECK, it.turnId, "valid")
        }, t, liveClockMs = { simulatedNow })
        loop.arm(true, true)
        check(loop.onVerifiedObservation(obs(remaining=2500), 1100, false, true, true).state ==
            AutoLoopState.BLOCKED_UNCERTAIN)
        check(t.sent == 0)
    }
    test("same hand window generation still required") {
        val cmd=LiveCommand("cmd","hand2",LiveAction.CHECK,2,14,"x",1100,1000,18)
        check(LiveDispatchFreshness.verify(cmd,1150,2,true,pkg,pkg,17)==LiveDispatchCheck.WINDOW_CHANGED)
        check(LiveDispatchFreshness.verify(cmd,1150,2,true,pkg,pkg,null)==LiveDispatchCheck.WINDOW_CHANGED)
        check(LiveDispatchFreshness.verify(cmd,1150,2,true,pkg,pkg,18)==LiveDispatchCheck.VALID)
    }
    println("AutonomousActionScenarios: $n checks passed")
}
