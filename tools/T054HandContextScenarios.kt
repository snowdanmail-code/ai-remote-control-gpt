package com.example.airemote

/** T054: offline position/stack/action math, never a source of game gestures. */
object T054HandContextScenarios {
    @JvmStatic fun main(args: Array<String>) {
        val source = PokerSourceIdentity("fixture", "table", "session", "hand")
        fun seats(capacity: Int) = (0 until capacity).map {
            CanonicalPokerSeat(it, participantId = "p$it",
                identityEvidence = PokerEvidence.INDEPENDENT,
                dealtIn = true, activeInHand = it in setOf(0, 1, 2),
                stackChips = when (it) { 0 -> 1000L; 1 -> 400L; 2 -> 700L; else -> 0L })
        }
        fun observation(capacity: Int) = CanonicalPokerObservation(
            source = source, observationId = "obs1", tableCapacity = capacity,
            rulesetId = PokerRulesets.STANDARD52_NO_LIMIT_V1,
            seats = seats(capacity), seatingComplete = true, heroSeat = 0,
            buttonSeat = 3, street = PokerStreet.FLOP,
            boardCards = listOf(CanonicalPokerCard("A","S"),
                CanonicalPokerCard("K","H"), CanonicalPokerCard("2","D")),
            potChips = 100L, observationEvidence = PokerEvidence.INDEPENDENT,
            actions = listOf(CanonicalPokerAction(1, PokerStreet.PREFLOP, 2,
                PokerActionKind.RAISE, 50L, PokerEvidence.INDEPENDENT, "evt1"))
        )
        val bets = T054IndependentBettingSnapshot(source, PokerStreet.FLOP,
            potBeforeHeroActionChips = 100L, costToCallChips = 25L,
            bigBlindChips = 10L, currentBetToChips = 100L,
            heroCommittedOnStreetChips = 75L, lastFullRaiseSizeChips = 50L,
            evidence = PokerEvidence.INDEPENDENT)
        var checks = 0
        fun verify(name: String, passed: Boolean) {
            check(passed) { name }
            checks++
            println("PASS T054 hand-context: $name")
        }
        val six = T054ReadOnlyHandContextBuilder.build(observation(6), bets)!!
        verify("six-max hero position", six.heroPosition == PokerSeatPosition.UNDER_THE_GUN)
        verify("active players and multiway", six.activeOpponents == 2 && six.isMultiway == true)
        verify("opponent-specific effective stacks",
            six.opponents.map { it.effectiveRemainingStackChips } == listOf(400L,700L))
        verify("minimum effective remaining stack and SPR",
            six.minimumEffectiveRemainingStackChips == 400L &&
                six.minimumEffectiveRemainingSpr == 4.0)
        verify("pot odds from verified pot and call",
            six.potOddsRequiredEquity == 0.2)
        verify("raise-to bounds are math not touch permission",
            six.mathematicalRaiseBounds?.fullRaiseMinimumTo == 150L &&
                six.history.single().amountSemanticsVerified == false)
        val nine = T054ReadOnlyHandContextBuilder.build(observation(9), bets)!!
        verify("nine-max hero position", nine.heroPosition == PokerSeatPosition.LOJACK)
        val unknownStack = observation(6).copy(seats = seats(6).map {
            if (it.seatIndex == 1) it.copy(stackChips = null) else it
        })
        val unknown = T054ReadOnlyHandContextBuilder.build(unknownStack,bets)!!
        verify("unknown villain stack cannot be inferred",
            unknown.minimumEffectiveRemainingStackChips == null &&
                "ACTIVE_OPPONENT_STACK" in unknown.unknownFields)
        verify("cross-hand cannot become a study packet",
            T054ReadOnlyHandContextBuilder.build(observation(6),
                bets.copy(source = source.copy(handId = "hand2"))) == null)
        verify("OCR-only cannot be treated as trusted state",
            T054ReadOnlyHandContextBuilder.build(observation(6).copy(
                observationEvidence = PokerEvidence.OCR_ONLY), bets) == null)
        verify("unverified call cost does not match street total",
            T054ReadOnlyHandContextBuilder.build(observation(6),
                bets.copy(costToCallChips = 30L)) == null)
        println("Passed $checks T054 hand-context JVM scenarios")
    }
}
