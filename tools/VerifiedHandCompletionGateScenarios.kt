package com.example.airemote

/** Pure JVM contract checks of event metadata, NOT a poker/game simulator. */
private class ReceiptStore : HandLearningStore {
    val seen = mutableSetOf<Pair<String,String>>()
    var writes = 0
    var last: VerifiedFinishedHand? = null
    var fail = false
    override fun commitHandAtomically(hand: VerifiedFinishedHand): HandLearningResult {
        if (fail) return HandLearningResult.STORAGE_ERROR
        val key = hand.tableKey to hand.handKey
        if (!seen.add(key)) return HandLearningResult.ALREADY_SEEN
        writes++
        last = hand
        return HandLearningResult.APPLIED
    }
    override fun opponentProfile(opponentKey: String): OpponentLearningSummary? = null
    override fun completedHandsCount() = seen.size
}

private fun f(seq: Long, phase: HandBoundaryPhase = HandBoundaryPhase.ACTIVE,
    id: String = "verified-hand-id-001", epoch: Long = 9L,
    cue: Boolean = phase == HandBoundaryPhase.TERMINAL,
    at: Long = 10000L + seq * 200L) = VerifiedHandBoundaryFrame(
    tableKey = "verified-table", handKey = id, captureEpoch = epoch,
    frameSequence = seq, capturedAtElapsedMs = at - 50L,
    inspectedAtElapsedMs = at, sourceVerified = true,
    stableHandKeyVerified = true, recognitionConfidence = 0.99,
    phase = phase, terminalCueVerified = cue,
    terminalCueKey = if (cue) "verified-result-panel" else null)

private fun armed(store: ReceiptStore = ReceiptStore()): Pair<VerifiedHandCompletionGate, ReceiptStore> {
    val learner = PostHandLearner(store)
    val gate = VerifiedHandCompletionGate(learner::onHandFinished)
    check(gate.ingest(f(1)).status == HandBoundaryStatus.WAITING_FOR_ACTIVE)
    check(gate.ingest(f(2)).status == HandBoundaryStatus.ACTIVE_CONFIRMED)
    return gate to store
}

fun main() {
    var n = 0
    fun test(name: String, body: () -> Unit) { body(); n++; println("PASS $name") }
    test("no terminal without two active observations") {
        val g = VerifiedHandCompletionGate(PostHandLearner(ReceiptStore())::onHandFinished)
        check(g.ingest(f(1, HandBoundaryPhase.TERMINAL)).status == HandBoundaryStatus.REJECTED)
    }
    test("one active frame cannot close hand") {
        val g = VerifiedHandCompletionGate(PostHandLearner(ReceiptStore())::onHandFinished)
        g.ingest(f(1)); check(g.ingest(f(2, HandBoundaryPhase.TERMINAL)).status == HandBoundaryStatus.REJECTED)
    }
    test("one terminal frame does not commit") {
        val (g,s) = armed(); check(g.ingest(f(3,HandBoundaryPhase.TERMINAL)).status == HandBoundaryStatus.WAITING_FOR_TERMINAL)
        check(s.writes == 0)
    }
    test("two confirmed terminal frames commit immediately") {
        val (g,s) = armed(); g.ingest(f(3,HandBoundaryPhase.TERMINAL))
        check(g.ingest(f(4,HandBoundaryPhase.TERMINAL)).status == HandBoundaryStatus.COMMITTED)
        check(s.writes == 1)
    }
    test("unknown outcome and opponent facts remain unknown") {
        val (g,s) = armed(); g.ingest(f(3,HandBoundaryPhase.TERMINAL)); g.ingest(f(4,HandBoundaryPhase.TERMINAL))
        check(s.last?.heroNetChips == null && s.last?.opponents?.isEmpty() == true)
    }
    test("repeat terminal frame does not double count") {
        val (g,s) = armed(); g.ingest(f(3,HandBoundaryPhase.TERMINAL)); g.ingest(f(4,HandBoundaryPhase.TERMINAL))
        check(g.ingest(f(5,HandBoundaryPhase.TERMINAL)).status == HandBoundaryStatus.ALREADY_COMMITTED)
        check(s.writes == 1)
    }
    test("receipt store prevents duplicates across gate restarts") {
        val s=ReceiptStore(); val (g,_) = armed(s); g.ingest(f(3,HandBoundaryPhase.TERMINAL)); g.ingest(f(4,HandBoundaryPhase.TERMINAL))
        val (g2,_) = armed(s); g2.ingest(f(3,HandBoundaryPhase.TERMINAL))
        check(g2.ingest(f(4,HandBoundaryPhase.TERMINAL)).status == HandBoundaryStatus.ALREADY_COMMITTED)
        check(s.writes == 1)
    }
    test("missing source proof rejects") {
        val (g,s) = armed(); check(g.ingest(f(3,HandBoundaryPhase.TERMINAL).copy(sourceVerified=false)).status == HandBoundaryStatus.REJECTED)
        check(s.writes == 0)
    }
    test("unverified hand identity rejects") {
        val (g,s) = armed(); check(g.ingest(f(3,HandBoundaryPhase.TERMINAL).copy(stableHandKeyVerified=false)).status == HandBoundaryStatus.REJECTED)
        check(s.writes == 0)
    }
    test("blank hand id rejects") {
        val (g,s) = armed(); check(g.ingest(f(3,HandBoundaryPhase.TERMINAL,id="")).status == HandBoundaryStatus.REJECTED)
        check(s.writes == 0)
    }
    test("absent terminal cue rejects, not inferred from missing cards") {
        val (g,s) = armed(); check(g.ingest(f(3,HandBoundaryPhase.TERMINAL,cue=false)).status == HandBoundaryStatus.REJECTED)
        check(s.writes == 0)
    }
    test("stale frames reject") {
        val (g,s) = armed(); check(g.ingest(f(3,HandBoundaryPhase.TERMINAL).copy(inspectedAtElapsedMs=14000)).status == HandBoundaryStatus.REJECTED)
        check(s.writes == 0)
    }
    test("low confidence rejects") {
        val (g,s) = armed(); check(g.ingest(f(3,HandBoundaryPhase.TERMINAL).copy(recognitionConfidence=.85)).status == HandBoundaryStatus.REJECTED)
        check(s.writes == 0)
    }
    test("reused frame sequence rejects") {
        val (g,s) = armed(); check(g.ingest(f(2,HandBoundaryPhase.TERMINAL)).status == HandBoundaryStatus.REJECTED)
        check(s.writes == 0)
    }
    test("capture epoch change invalidates old hand") {
        val (g,s) = armed(); check(g.ingest(f(3,HandBoundaryPhase.TERMINAL,epoch=10)).status == HandBoundaryStatus.REJECTED)
        check(s.writes == 0)
    }
    test("table change invalidates old hand") {
        val (g,s) = armed(); check(g.ingest(f(3,HandBoundaryPhase.TERMINAL).copy(tableKey="different")).status == HandBoundaryStatus.REJECTED)
        check(s.writes == 0)
    }
    test("new hand id cannot finish old hand") {
        val (g,s) = armed(); check(g.ingest(f(3,HandBoundaryPhase.TERMINAL,id="next-id")).status == HandBoundaryStatus.REJECTED)
        check(s.writes == 0)
    }
    test("STOP blocks and resets evidence") {
        val (g,s) = armed(); g.ingest(f(3,HandBoundaryPhase.TERMINAL))
        check(g.ingest(f(4,HandBoundaryPhase.TERMINAL).copy(operatorStopped=true)).status == HandBoundaryStatus.STOPPED)
        check(g.ingest(f(5,HandBoundaryPhase.TERMINAL)).status == HandBoundaryStatus.REJECTED)
        check(s.writes == 0)
    }
    test("terminal cue must match across frames") {
        val (g,s) = armed(); g.ingest(f(3,HandBoundaryPhase.TERMINAL))
        check(g.ingest(f(4,HandBoundaryPhase.TERMINAL).copy(terminalCueKey="other"))
            .status == HandBoundaryStatus.WAITING_FOR_TERMINAL)
        check(s.writes == 0)
    }
    test("two terminal frames too close cannot commit") {
        val (g,s) = armed(); g.ingest(f(3,HandBoundaryPhase.TERMINAL))
        check(g.ingest(f(4,HandBoundaryPhase.TERMINAL,at=10650)).status == HandBoundaryStatus.WAITING_FOR_TERMINAL)
        check(s.writes == 0)
    }
    test("terminal frames too far apart restart confirmation") {
        val (g,s) = armed(); g.ingest(f(3,HandBoundaryPhase.TERMINAL))
        check(g.ingest(f(4,HandBoundaryPhase.TERMINAL,at=14000)).status == HandBoundaryStatus.WAITING_FOR_TERMINAL)
        check(s.writes == 0)
    }
    test("resumed active phase cancels pending terminal") {
        val (g,s) = armed(); g.ingest(f(3,HandBoundaryPhase.TERMINAL))
        g.ingest(f(4)); check(g.ingest(f(5,HandBoundaryPhase.TERMINAL)).status == HandBoundaryStatus.WAITING_FOR_TERMINAL)
        check(s.writes == 0)
    }
    test("verified facts with wrong hand key rejected") {
        val (g,s) = armed(); g.ingest(f(3,HandBoundaryPhase.TERMINAL))
        val wrong = VerifiedFinishedHand("verified-table", "WRONG",9,1,4,10800,true,true,emptyList())
        check(g.ingest(f(4,HandBoundaryPhase.TERMINAL),wrong).status == HandBoundaryStatus.REJECTED)
        check(s.writes == 0)
    }
    test("verified facts can be atomically saved at finish") {
        val (g,s) = armed(); g.ingest(f(3,HandBoundaryPhase.TERMINAL))
        val facts = VerifiedFinishedHand("verified-table", "verified-hand-id-001",9,1,4,10800,true,true,
            listOf(OpponentHandFacts("verified-session/player",true,vpip=true)),heroNetChips=null)
        check(g.ingest(f(4,HandBoundaryPhase.TERMINAL),facts).status == HandBoundaryStatus.COMMITTED)
        check(s.last?.opponents?.single()?.vpip == true)
    }
    test("failed storage is not marked committed") {
        val s=ReceiptStore(); val (g,_) = armed(s); g.ingest(f(3,HandBoundaryPhase.TERMINAL))
        s.fail=true; check(g.ingest(f(4,HandBoundaryPhase.TERMINAL)).status == HandBoundaryStatus.STORAGE_ERROR)
        s.fail=false; check(g.ingest(f(5,HandBoundaryPhase.TERMINAL)).status == HandBoundaryStatus.COMMITTED)
        check(s.writes == 1)
    }
    test("invalid verified facts do not commit") {
        val (g,s)=armed(); g.ingest(f(3,HandBoundaryPhase.TERMINAL))
        val invalid=VerifiedFinishedHand("verified-table", "verified-hand-id-001",9,1,4,10800,true,true,
            listOf(OpponentHandFacts("opponent",true,showdownWon=true,showdown=false)))
        check(g.ingest(f(4,HandBoundaryPhase.TERMINAL),invalid).status == HandBoundaryStatus.REJECTED)
        check(s.writes == 0)
    }
    println("Passed $n VerifiedHandCompletionGate contract checks")
}
