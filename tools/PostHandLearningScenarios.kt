package com.example.airemote

/** Stand-alone Kotlin/JVM scenarios, no Android runtime or network. */
private class InMemoryAtomicStore : HandLearningStore {
    val receipts = mutableSetOf<Pair<String, String>>()
    val summaries = mutableMapOf<String, OpponentLearningSummary>()
    override fun commitHandAtomically(hand: VerifiedFinishedHand): HandLearningResult {
        val receipt = hand.tableKey to hand.handKey
        if (receipt in receipts) return HandLearningResult.ALREADY_SEEN
        val next = summaries.toMutableMap()
        hand.opponents.filter { it.identityVerified }.forEach { o ->
            val s = next[o.opponentKey] ?: OpponentLearningSummary(o.opponentKey,0,0,0,0,0,0,0,0,0,0,0)
            next[o.opponentKey] = s.copy(
                observedHands = s.observedHands + 1,
                vpipSeen = s.vpipSeen + if (o.vpip == true) 1 else 0,
                vpipOpportunities = s.vpipOpportunities + if (o.vpip != null) 1 else 0,
                pfrSeen = s.pfrSeen + if (o.preflopRaise == true) 1 else 0,
                pfrOpportunities = s.pfrOpportunities + if (o.preflopRaise != null) 1 else 0,
                aggressionActions = s.aggressionActions + (o.postflopBetsOrRaises ?: 0),
                postflopCalls = s.postflopCalls + (o.postflopCalls ?: 0),
                foldsFacingBet = s.foldsFacingBet + if (o.foldedWhenFacingBet == true) 1 else 0,
                facedBetOpportunities = s.facedBetOpportunities + if (o.foldedWhenFacingBet != null) 1 else 0,
                showdownsSeen = s.showdownsSeen + if (o.showdown == true) 1 else 0,
                showdownsWon = s.showdownsWon + if (o.showdownWon == true) 1 else 0
            )
        }
        summaries.clear(); summaries.putAll(next); receipts.add(receipt)
        return HandLearningResult.APPLIED
    }
    override fun opponentProfile(opponentKey: String) = summaries[opponentKey]
    override fun completedHandsCount() = receipts.size
}

private fun finished(id: String, opponents: List<OpponentHandFacts> = listOf(
    OpponentHandFacts("table-a/session-1/player-1", true, true, false, 2, 1, true, false, true, false)
)): VerifiedFinishedHand = VerifiedFinishedHand("table-a", id, 2, 100, 130, 50000,
    sourceVerified = true, terminalConfirmedByIndependentFrames = true, opponents = opponents,
    heroNetChips = 25.0)

fun main() {
    var passed = 0
    fun case(name: String, fn: () -> Unit) { fn(); passed++; println("PASS $name") }
    val store = InMemoryAtomicStore()
    val learning = PostHandLearner(store)
    val oppKey = "table-a/session-1/player-1"
    case("finished hand immediately applied") { check(learning.onHandFinished(finished("hand-1")) == HandLearningResult.APPLIED) }
    case("stats visible before next hand") { check(learning.profileForNextDecision(oppKey)?.observedHands == 1) }
    case("vpip immediately visible") { check(learning.profileForNextDecision(oppKey)?.vpipSeen == 1) }
    case("pfr observed denominator") { check(learning.profileForNextDecision(oppKey)?.pfrOpportunities == 1) }
    case("aggression updates immediately") { check(learning.profileForNextDecision(oppKey)?.aggressionActions == 2) }
    case("single terminal event is idempotent") { check(learning.onHandFinished(finished("hand-1")) == HandLearningResult.ALREADY_SEEN) }
    case("idempotence keeps counter unchanged") { check(learning.profileForNextDecision(oppKey)?.observedHands == 1) }
    case("another hand incremented") { check(learning.onHandFinished(finished("hand-2")) == HandLearningResult.APPLIED) }
    case("two hands visible") { check(learning.profileForNextDecision(oppKey)?.observedHands == 2) }
    case("same key on different table distinct") { check(learning.onHandFinished(finished("hand-1").copy(tableKey="table-b")) == HandLearningResult.APPLIED) }
    case("unverified source rejected") { check(learning.onHandFinished(finished("hand-3").copy(sourceVerified=false)) == HandLearningResult.REJECTED) }
    case("unconfirmed finish rejected") { check(learning.onHandFinished(finished("hand-3").copy(terminalConfirmedByIndependentFrames=false)) == HandLearningResult.REJECTED) }
    case("missing hand id rejected") { check(learning.onHandFinished(finished("")) == HandLearningResult.REJECTED) }
    case("same frame rejected") { check(learning.onHandFinished(finished("hand-3").copy(terminalFrame=100)) == HandLearningResult.REJECTED) }
    case("invalid time rejected") { check(learning.onHandFinished(finished("hand-3").copy(finishedAtElapsedMs=-1)) == HandLearningResult.REJECTED) }
    case("negative capture epoch rejected") { check(learning.onHandFinished(finished("hand-3").copy(captureEpoch=-1)) == HandLearningResult.REJECTED) }
    case("anonymous player not merged but hand counted") {
        check(learning.onHandFinished(finished("hand-anon",listOf(OpponentHandFacts("seat4",false)))) == HandLearningResult.APPLIED)
        check(learning.profileForNextDecision("seat4") == null)
    }
    case("opponent duplicate rejected") { val o = OpponentHandFacts("p",true); check(learning.onHandFinished(finished("hand-3",listOf(o,o))) == HandLearningResult.REJECTED) }
    case("invalid fold without facing bet rejected") { check(learning.onHandFinished(finished("hand-3", listOf(OpponentHandFacts("p",true,facedBet=false,foldedWhenFacingBet=true)))) == HandLearningResult.REJECTED) }
    case("showdown win must be seen") { check(learning.onHandFinished(finished("hand-3", listOf(OpponentHandFacts("p",true,showdown=false,showdownWon=true)))) == HandLearningResult.REJECTED) }
    case("NaN outcome rejected") { check(learning.onHandFinished(finished("hand-3").copy(heroNetChips=Double.NaN)) == HandLearningResult.REJECTED) }
    case("negative action count rejected") { check(learning.onHandFinished(finished("hand-3",listOf(OpponentHandFacts("p",true,postflopCalls=-2)))) == HandLearningResult.REJECTED) }
    case("unknown facts do not fill denominators") {
        check(learning.onHandFinished(finished("hand-3",listOf(OpponentHandFacts("p",true)))) == HandLearningResult.APPLIED)
        check(learning.profileForNextDecision("p")?.vpipOpportunities == 0)
    }
    case("unknown fold-vs-bet outcome never counts as opportunity") {
        check(learning.onHandFinished(finished("unknown-fold", listOf(
            OpponentHandFacts("p", true, facedBet = true, foldedWhenFacingBet = null)))) == HandLearningResult.APPLIED)
        check(learning.profileForNextDecision("p")?.facedBetOpportunities == 0)
    }
    case("false fold needs verified facing bet") {
        check(learning.onHandFinished(finished("false-without-facing", listOf(
            OpponentHandFacts("p", true, facedBet = null, foldedWhenFacingBet = false)))) == HandLearningResult.REJECTED)
    }
    case("missing facts indicated in context") { check("недостаточно данных" in learning.profileForNextDecision("p")!!.contextLine()) }
    case("unconfirmed own action rejected") {
        val bad = VerifiedExecutedAction("a", "FLOP", "CALL", 102, false)
        check(learning.onHandFinished(finished("hand-5").copy(verifiedHeroActions=listOf(bad))) == HandLearningResult.REJECTED)
    }
    case("confirmed own action accepted") {
        val ok = VerifiedExecutedAction("a", "FLOP", "CALL", 102, true)
        check(learning.onHandFinished(finished("hand-5").copy(verifiedHeroActions=listOf(ok))) == HandLearningResult.APPLIED)
    }
    case("future own action frame rejected") {
        val bad = VerifiedExecutedAction("a", "TURN", "RAISE", 300, true)
        check(learning.onHandFinished(finished("hand-6").copy(verifiedHeroActions=listOf(bad))) == HandLearningResult.REJECTED)
    }
    case("context includes updated opponent") { check("VPIP" in learning.contextForNextDecision(listOf(oppKey))) }
    case("failed attempt does not count") { check(store.completedHandsCount() == 7) }
    case("concurrent duplicate end events count once") {
        val concurrentStore = InMemoryAtomicStore()
        val concurrentLearner = PostHandLearner(concurrentStore)
        val jobs = (1..25).map { Thread { concurrentLearner.onHandFinished(finished("concurrent")) } }
        jobs.forEach { it.start() }; jobs.forEach { it.join() }
        check(concurrentStore.completedHandsCount() == 1)
    }
    println("Passed $passed PostHandLearning JVM scenarios")
}
