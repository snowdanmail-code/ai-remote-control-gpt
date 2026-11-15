package com.example.airemote

/** T054 deterministic, no Android/GPT/network; runs in pure JVM CI. */
object T054OpponentOpportunityScenarios {
    @JvmStatic fun main(args: Array<String>) {
        fun event(id: String, a: T054PreflopEventKind) =
            T054VerifiedPreflopEvent(id, a, true)
        fun of(
            complete: Boolean, actions: List<T054VerifiedPreflopEvent>,
            faced: List<T054VerifiedFacingBetDecision> = emptyList()
        ) = T054OpponentOpportunityTranscript("verified-player", true, true,
            complete, complete, actions, faced)

        val baseline = T054VerifiedOpponentOpportunities.derive(
            of(true, listOf(event("e0", T054PreflopEventKind.POST_BLIND),
                event("e1", T054PreflopEventKind.FOLD))))!!
        check(baseline.handFacts.vpip == false && baseline.handFacts.preflopRaise == false)
        println("PASS full authenticated preflop permits known negative VPIP/PFR")

        val incomplete = T054VerifiedOpponentOpportunities.derive(
            of(false, listOf(event("e1", T054PreflopEventKind.FOLD))))!!
        check(incomplete.handFacts.vpip == null && incomplete.handFacts.preflopRaise == null)
        println("PASS missing events never become negative VPIP/PFR")

        val partialRaise = T054VerifiedOpponentOpportunities.derive(
            of(false, listOf(event("e1", T054PreflopEventKind.RAISE))))!!
        check(partialRaise.handFacts.vpip == true && partialRaise.handFacts.preflopRaise == true)
        println("PASS isolated independently verified raise proves positives")

        val two = T054VerifiedOpponentOpportunities.derive(of(true,
            listOf(event("e0", T054PreflopEventKind.CALL)),
            listOf(T054VerifiedFacingBetDecision("o1", T054FacingBetChoice.CALL, true),
                T054VerifiedFacingBetDecision("o2", T054FacingBetChoice.FOLD, true))))!!
        check(two.handFacts.vpip == true && two.handFacts.preflopRaise == false)
        check(two.verifiedFacingBetOpportunities == 2 && two.verifiedFoldsToBet == 1)
        check(two.handFacts.foldedWhenFacingBet == null)
        println("PASS second facing-bet decision cannot be lost as v2 Boolean")

        check(T054VerifiedOpponentOpportunities.derive(of(true,
            listOf(event("e0", T054PreflopEventKind.CALL).copy(
                independentlyVerified = false)))) == null)
        println("PASS unverified actions cannot write opportunity statistics")

        println("Passed 5 T054 opponent opportunity scenarios")
    }
}
