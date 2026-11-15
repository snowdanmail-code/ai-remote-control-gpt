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
            stacksAreRemainingBehind = true, evidence = PokerEvidence.INDEPENDENT)
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
        val shortStack = observation(6).copy(seats = seats(6).map {
            if (it.seatIndex == 0) it.copy(stackChips = 10L) else it
        })
        val missingStreetBet = bets.copy(currentBetToChips = null, costToCallChips = 10L)
        val possibleShort = T054ReadOnlyHandContextBuilder.build(shortStack, missingStreetBet)!!
        verify("an all-in call with unknown street bet cannot use the whole pot",
            possibleShort.costToCallChips == 10L && possibleShort.potOddsRequiredEquity == null &&
                "ALL_IN_ELIGIBLE_POT" in possibleShort.unknownFields)
        verify("a call above the verified stack is contradictory even without a street bet",
            T054ReadOnlyHandContextBuilder.build(shortStack,
                missingStreetBet.copy(costToCallChips = 11L)) == null)
        val unknownStackAndBet = T054ReadOnlyHandContextBuilder.build(shortStack,
            missingStreetBet.copy(stacksAreRemainingBehind = false))!!
        verify("missing stack timing and street bet cannot rule out a short call",
            unknownStackAndBet.potOddsRequiredEquity == null)
        val fullCall = T054ReadOnlyHandContextBuilder.build(observation(6), missingStreetBet)!!
        verify("a call below the verified stack still uses the attested pot",
            fullCall.potOddsRequiredEquity == PokerMath.potOddsRequiredEquity(100L, 10L))
        val exactAllIn = T054ReadOnlyHandContextBuilder.build(shortStack,
            missingStreetBet.copy(currentBetToChips = 85L))!!
        verify("a verified full call can exactly match the hero stack",
            exactAllIn.potOddsRequiredEquity == PokerMath.potOddsRequiredEquity(100L, 10L))
        val preflopRaise = observation(6).actions.single()
        val flopCheck = CanonicalPokerAction(2, PokerStreet.FLOP, 1,
            PokerActionKind.CHECK, null, PokerEvidence.INDEPENDENT, "evt2")
        val unknownStreet = flopCheck.copy(sequence = 3, street = PokerStreet.UNKNOWN, eventId = "evt3")
        val futureBet = flopCheck.copy(sequence = 4, street = PokerStreet.TURN, eventId = "evt4")
        verify("future streets cannot enter the current hand context",
            T054ReadOnlyHandContextBuilder.build(
                observation(6).copy(actions = listOf(preflopRaise, flopCheck, futureBet)), bets) == null)
        val latePreflop = preflopRaise.copy(sequence = 4, eventId = "evt4")
        verify("unknown street does not hide a backwards street transition",
            T054ReadOnlyHandContextBuilder.build(
                observation(6).copy(actions = listOf(flopCheck, unknownStreet, latePreflop)), bets) == null)
        val validPartial = T054ReadOnlyHandContextBuilder.build(
            observation(6).copy(actions = listOf(preflopRaise, flopCheck, unknownStreet)), bets)!!
        verify("partial but consistent action history retains unknown street evidence",
            validPartial.history.map { it.street } ==
                listOf(PokerStreet.PREFLOP, PokerStreet.FLOP, PokerStreet.UNKNOWN))
        val allInObservation = observation(6).copy(potChips = 350L, seats = seats(6).map {
            when (it.seatIndex) {
                0 -> it.copy(stackChips = 50L)
                3, 4, 5 -> it.copy(dealtIn = false)
                else -> it
            }
        })
        val allInBets = bets.copy(potBeforeHeroActionChips = 350L, costToCallChips = 50L,
            currentBetToChips = 150L, heroCommittedOnStreetChips = 50L,
            contributionsBeforeCallBySeat = mapOf(0 to 50L, 1 to 150L, 2 to 150L))
        val allocated = T054ReadOnlyHandContextBuilder.build(allInObservation, allInBets)!!
        verify("short call prices only the pot HERO can win",
            allocated.potBeforeHeroActionChips == 350L &&
                allocated.potForCallChips == 250L &&
                allocated.potOddsRequiredEquity == 1.0 / 6.0)
        verify("every dealt-in seat is required for side-pot math",
            T054ReadOnlyHandContextBuilder.build(allInObservation, allInBets.copy(
                contributionsBeforeCallBySeat = mapOf(0 to 50L, 1 to 300L))) == null)
        verify("contributions must reconcile with the attested pre-call pot",
            T054ReadOnlyHandContextBuilder.build(allInObservation, allInBets.copy(
                contributionsBeforeCallBySeat = mapOf(0 to 50L, 1 to 150L, 2 to 151L))) == null)
        verify("total contribution cannot be smaller than HERO's current street commitment",
            T054ReadOnlyHandContextBuilder.build(allInObservation, allInBets.copy(
                contributionsBeforeCallBySeat = mapOf(0 to 40L, 1 to 160L, 2 to 150L))) == null)
        val withFolded = allInObservation.copy(potChips = 375L,
            seats = allInObservation.seats.map {
                if (it.seatIndex == 3) it.copy(dealtIn = true) else it
            })
        val withDeadMoney = T054ReadOnlyHandContextBuilder.build(withFolded, allInBets.copy(
            potBeforeHeroActionChips = 375L,
            contributionsBeforeCallBySeat = mapOf(0 to 50L, 1 to 150L, 2 to 150L, 3 to 25L)))!!
        verify("folded chips count towards the pot but never towards eligibility",
            withDeadMoney.potForCallChips == 275L)
        println("Passed $checks T054 hand-context JVM scenarios")
    }
}
