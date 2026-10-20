package com.example.airemote

import java.io.File
import kotlin.math.abs
import kotlin.random.Random

/** Main-Class: com.example.airemote.PokerMathScenarios; standalone JVM assertions. */
object PokerMathScenarios {
    private var checks = 0
    private fun verify(value: Boolean, description: String) {
        check(value) { "PokerMath failure: $description" }
        checks++
    }
    private fun cards(text: String): List<PokerCard> = text.split(' ').map(PokerCard::parse)
    private fun approximately(actual: Double, expected: Double, label: String) =
        verify(abs(actual - expected) < 1e-12, "$label: $actual != $expected")
    private fun fails(label: String, block: () -> Unit) {
        val threw = try { block(); false } catch (_: IllegalArgumentException) { true } catch (_: ArithmeticException) { true }
        verify(threw, label)
    }

    @JvmStatic fun main(args: Array<String>) {
        val fixturePath = args.firstOrNull() ?: "tools/fixtures/poker_oracle.tsv"
        val fixtures = File(fixturePath).readLines().filter { it.isNotBlank() }
        verify(fixtures.size >= 500, "independent reference fixture exists")
        for ((index, line) in fixtures.withIndex()) {
            val (hand, cat, tie) = line.split('\t')
            val expected = PokerHandValue(PokerHandClass.entries[cat.toInt()], tie.split(',').map(String::toInt))
            val actual = PokerMath.evaluate(cards(hand))
            verify(actual == expected, "independent reference mismatch #$index: $actual vs $expected")
        }
        // Physical duplicates cannot appear even if a screen repeats the same card image.
        fails("duplicate five") { PokerMath.evaluate(cards("As As 2d 3d 4d")) }
        fails("duplicate seven") { PokerMath.evaluate(cards("As 2h 3s 4d 5c 6s As")) }
        fails("invalid card") { PokerCard.parse("1z") }
        fails("invalid card length") { PokerCard.parse("A") }
        fails("invalid hand size") { PokerMath.evaluate(cards("As Kh Qd")) }
        verify(PokerCard.parse("10H") == PokerCard.parse("Th"), "ten alias")
        verify(PokerCard.parse(" aS ").toString() == "As", "case normalization")
        val wheel = PokerMath.evaluate(cards("As 2d 3h 4c 5s Qd Kc"))
        val sixHigh = PokerMath.evaluate(cards("2d 3h 4c 5s 6d Qd Kc"))
        verify(wheel.category == PokerHandClass.STRAIGHT && wheel.tiebreak == listOf(5), "ace-low wheel")
        verify(sixHigh > wheel, "six high beats wheel")
        verify(PokerMath.evaluate(cards("As Ac Ad Kh Kd Ks 2c")).tiebreak == listOf(14, 13),
            "best full house with two trips")
        verify(PokerMath.evaluate(cards("As Ac Kh Kd Qs Qd 2c")).tiebreak == listOf(14, 13, 12),
            "highest two pairs and best kicker")
        verify(PokerMath.evaluate(cards("As Ac Ad Ah Ks Qd 2c")).tiebreak == listOf(14, 13),
            "four of a kind kicker")
        val fixedHand = cards("As Ac Kh Kd Qd Js Ts")
        val random = Random(123)
        repeat(200) { verify(PokerMath.evaluate(fixedHand.shuffled(random)) == PokerMath.evaluate(fixedHand),
            "permutation invariance #$it") }
        for (cat in 0..7) verify(PokerHandValue(PokerHandClass.entries[cat+1], emptyList()) >
            PokerHandValue(PokerHandClass.entries[cat], emptyList()), "rank category #$cat")
        approximately(PokerMath.potOddsRequiredEquity(100L, 50L), 1.0/3.0, "pot odds call")
        approximately(PokerMath.potOddsRequiredEquity(100L, 0L), 0.0, "free check")
        approximately(PokerMath.potOddsRequiredEquity(0L, 50L), 1.0, "zero pot")
        approximately(PokerMath.potOddsRequiredEquity(Long.MAX_VALUE, Long.MAX_VALUE), 0.5,
            "huge pot no overflow")
        fails("negative pot") { PokerMath.potOddsRequiredEquity(-1, 10) }
        fails("negative call") { PokerMath.potOddsRequiredEquity(10, -1) }
        verify(PokerMath.effectiveStack(200, 100) == 100L, "effective stack")
        fails("negative stack") { PokerMath.effectiveStack(100, -1) }
        val raise = PokerMath.raiseBounds(100, 20, 200, 80)
        verify(raise.fullRaiseMinimumTo == 180L && raise.maximumTo == 220L && raise.shortAllInTo == null,
            "full raise bounds")
        val short = PokerMath.raiseBounds(100, 20, 130, 80)
        verify(short.fullRaiseMinimumTo == null && short.maximumTo == 150L && short.shortAllInTo == 150L,
            "short all in is not a full raise")
        verify(PokerMath.raiseBounds(100, 20, 80, 80).maximumTo == null, "cannot raise if only call")
        verify(PokerMath.raiseBounds(0, 0, 100, 20).fullRaiseMinimumTo == 20L,
            "minimum unopened bet")
        fails("invalid committed chips") { PokerMath.raiseBounds(100, 101, 20, 20) }
        fails("overflow raise") { PokerMath.raiseBounds(Long.MAX_VALUE-1, 0, Long.MAX_VALUE, 20) }
        val side = PokerMath.sidePots(mapOf("A" to 100L, "B" to 200L, "C" to 300L), emptySet())
        verify(side.pots.map { it.amount } == listOf(300L, 200L), "side pot amounts")
        verify(side.pots.map { it.eligiblePlayers.size } == listOf(3, 2), "side pot eligibles")
        verify(side.uncalledReturns == mapOf("C" to 100L), "uncalled return")
        verify(side.contestedTotal == 500L, "conservation of pots")
        val folded = PokerMath.sidePots(mapOf("A" to 100L, "B" to 200L, "C" to 200L), setOf("B"))
        verify(folded.pots[0].eligiblePlayers == setOf("A", "C"), "folded money not eligible main")
        verify(folded.pots[1].eligiblePlayers == setOf("C"), "folded money can be won by sole eligible")
        verify(folded.contestedTotal == 500L, "folded chips preserved")
        val zero = PokerMath.sidePots(mapOf("A" to 0L, "B" to 0L), emptySet())
        verify(zero.pots.isEmpty() && zero.uncalledReturns.isEmpty(), "zero contribution")
        val solo = PokerMath.sidePots(mapOf("A" to 200L, "B" to 0L), emptySet())
        verify(solo.pots.isEmpty() && solo.uncalledReturns == mapOf("A" to 200L), "unmatched only")
        fails("negative contribution") { PokerMath.sidePots(mapOf("A" to -1L), emptySet()) }
        fails("unknown folded") { PokerMath.sidePots(mapOf("A" to 1L), setOf("B")) }
        fails("folded-only contested") {
            PokerMath.sidePots(mapOf("A" to 50L, "B" to 50L), setOf("A", "B"))
        }
        fails("folded player cannot reclaim unmatched excess") {
            PokerMath.sidePots(mapOf("A" to 50L, "B" to 100L), setOf("B"))
        }
        // Integer-chip split: 5 chips among two equal hands => 3/2, with odd chip
        // awarded according to dealer-relative priority, never dictionary order.
        val sharedRoyal = cards("As Ks Qs Js Ts")
        val splitSettlement = PokerMath.settleShowdown(
            mapOf("B" to 3L, "A" to 2L), emptySet(),
            mapOf("A" to cards("2c 3h"), "B" to cards("4d 5c")), sharedRoyal,
            listOf("B", "A"))
        verify(splitSettlement.payouts == mapOf("B" to 3L, "A" to 2L),
            "odd chip assigned by dealer-relative order (includes 1 chip return)")
        val splitOdd = PokerMath.settleShowdown(
            mapOf("A" to 3L, "B" to 3L, "C" to 3L), setOf("C"),
            mapOf("A" to cards("2c 3h"), "B" to cards("4d 5c")), sharedRoyal,
            listOf("B", "A", "C"))
        verify(splitOdd.payouts == mapOf("A" to 4L, "B" to 5L, "C" to 0L),
            "folded dead money split with odd chip")
        verify(splitOdd.layerWinners.single() == setOf("A", "B"), "equal showdown winners")
        val multiPot = PokerMath.settleShowdown(
            mapOf("A" to 100L, "B" to 200L, "C" to 300L), emptySet(),
            mapOf("A" to cards("As Ah"), "B" to cards("Ks Kh"), "C" to cards("2h 3h")),
            cards("2c 4d 7s 9h Jc"), listOf("C", "B", "A"))
        verify(multiPot.payouts == mapOf("A" to 300L, "B" to 200L, "C" to 100L),
            "main pot to A, side pot to B, uncalled overbet to C")
        fails("missing showdown hole cards") {
            PokerMath.settleShowdown(mapOf("A" to 10L, "B" to 10L), emptySet(),
                mapOf("A" to cards("As Ah")), cards("2c 3d 7s 9h Jc"), listOf("A", "B"))
        }
        fails("missing explicit odd-chip order") {
            PokerMath.settleShowdown(mapOf("A" to 10L, "B" to 10L), emptySet(),
                mapOf("A" to cards("As Ah"), "B" to cards("Ks Kh")),
                cards("2c 3d 7s 9h Jc"), emptyList())
        }
        fails("showdown duplicate cards across players") {
            PokerMath.settleShowdown(mapOf("A" to 10L, "B" to 10L), emptySet(),
                mapOf("A" to cards("As Ah"), "B" to cards("As Kh")),
                cards("2c 3d 7s 9h Jc"), listOf("A", "B"))
        }
        val riverHeroWins = PokerMath.equityVsKnownHands(cards("As Ah"),
            listOf(cards("Kc Kd")), cards("2c 3d 7s 9h Jc"))
        verify(riverHeroWins.exact && riverHeroWins.runouts == 1, "river deterministic")
        approximately(riverHeroWins.winProbability, 1.0, "river hero win")
        val riverHeroLoses = PokerMath.equityVsKnownHands(cards("As Ah"),
            listOf(cards("Ks Kd")), cards("2c 3d 7s 9h Kc"))
        approximately(riverHeroLoses.lossProbability, 1.0, "river hero loses")
        val boardTie = cards("As Ks Qs Js Ts")
        val riverSplit = PokerMath.equityVsKnownHands(cards("2c 3d"),
            listOf(cards("4h 5c")), boardTie)
        approximately(riverSplit.tieProbability, 1.0, "board tie")
        approximately(riverSplit.potShareEquity, 0.5, "board split")
        val threeWay = PokerMath.equityVsKnownHands(cards("2c 3d"),
            listOf(cards("4h 5c"), cards("6d 7h")), boardTie)
        approximately(threeWay.potShareEquity, 1.0/3.0, "three-way split")
        val turnExact = PokerMath.equityVsKnownHands(cards("As Ah"),
            listOf(cards("Kc Kd")), cards("2c 3d 7s 9h"))
        verify(turnExact.exact && turnExact.runouts == 44, "turn 44 exhaustive runouts")
        approximately(turnExact.winProbability + turnExact.tieProbability +
            turnExact.lossProbability, 1.0, "turn sum to one")
        val flop = PokerMath.equityVsKnownHands(cards("As Ah"),
            listOf(cards("Kc Kd")), cards("2c 3d 7s"))
        verify(flop.exact && flop.runouts == 990, "flop exhaustive 990")
        val mc1 = PokerMath.equityVsKnownHands(cards("As Ah"),
            listOf(cards("Kc Kd")), samples = 300, seed = 101)
        val mc2 = PokerMath.equityVsKnownHands(cards("As Ah"),
            listOf(cards("Kc Kd")), samples = 300, seed = 101)
        verify(!mc1.exact && mc1.runouts == 300 && mc1 == mc2, "seeded Monte Carlo reproducible")
        approximately(mc1.winProbability + mc1.tieProbability + mc1.lossProbability, 1.0,
            "Monte Carlo probabilities")
        verify(mc1.potShareEquity in 0.0..1.0, "Monte Carlo share bound")
        fails("duplicate hole cards in equity") {
            PokerMath.equityVsKnownHands(cards("As Ah"), listOf(cards("As Kd")), boardTie)
        }
        fails("empty opponents") { PokerMath.equityVsKnownHands(cards("As Ah"), emptyList()) }
        fails("invalid board length") { PokerMath.equityVsKnownHands(cards("As Ah"),
            listOf(cards("Kc Kd")), cards("2c 3d 7s 9h Tc Qd")) }
        fails("invalid samples") { PokerMath.equityVsKnownHands(cards("As Ah"),
            listOf(cards("Kc Kd")), samples = 0) }
        val proven = VerifiedPokerMathInput("t1", "h1", 1L, 1L, true, true,
            HandStage.FLOP, cards("As Ah"), cards("2c 3d 7s"),
            potBeforeCall = 100L, callChips = 50L, currentStreetBet = 100L,
            playerStreetCommitted = 0L, stackBehind = 300L, minimumFullRaiseIncrement = 100L)
        val math = VerifiedPokerMathBridge.analyze(proven)
        verify(math != null && math.bestHand?.category == PokerHandClass.ONE_PAIR,
            "verified flop hand strength")
        approximately(requireNotNull(math).potOddsRequiredEquity ?: -1.0, 1.0/3.0, "verified pot odds")
        verify(math.raiseBounds?.fullRaiseMinimumTo == 200L, "verified raise information")
        verify(VerifiedPokerMathBridge.analyze(proven.copy(independentTableHandProof = false)) == null,
            "no independent proof no diagnostics")
        verify(VerifiedPokerMathBridge.analyze(proven.copy(frameIsCurrent = false)) == null,
            "stale math source rejected")
        verify(VerifiedPokerMathBridge.analyze(proven.copy(tableId = "")) == null,
            "missing table id rejected")
        verify(VerifiedPokerMathBridge.analyze(proven.copy(handId = "")) == null,
            "missing hand id rejected")
        verify(VerifiedPokerMathBridge.analyze(proven.copy(captureEpoch = 0L)) == null,
            "missing epoch rejected")
        verify(VerifiedPokerMathBridge.analyze(proven.copy(frameSequence = 0L)) == null,
            "missing sequence rejected")
        verify(VerifiedPokerMathBridge.analyze(proven.copy(phase = HandStage.TURN)) == null,
            "board mismatched to stage")
        verify(VerifiedPokerMathBridge.analyze(proven.copy(boardCards = cards("2c 3d As"))) == null,
            "duplicate OCR card rejected")
        verify(VerifiedPokerMathBridge.analyze(proven.copy(phase = HandStage.SHOWDOWN)) == null,
            "showdown is not a betting state")
        verify(VerifiedPokerMathBridge.analyze(proven.copy(potBeforeCall = -1L)) == null,
            "negative OCR pot rejected")
        verify(VerifiedPokerMathBridge.analyze(proven.copy(currentStreetBet = Long.MAX_VALUE - 1,
            playerStreetCommitted = 0L, stackBehind = Long.MAX_VALUE,
            minimumFullRaiseIncrement = 50L)) == null, "overflowing raise rejected")
        val missingOcr = VerifiedPokerMathBridge.analyze(proven.copy(
            potBeforeCall = null, callChips = null, currentStreetBet = null,
            playerStreetCommitted = null, stackBehind = null, minimumFullRaiseIncrement = null))
        verify(missingOcr?.potOddsRequiredEquity == null && missingOcr?.raiseBounds == null,
            "unknown amounts are not fabricated")
        val preflop = VerifiedPokerMathBridge.analyze(proven.copy(phase = HandStage.PREFLOP,
            boardCards = emptyList()))
        verify(preflop?.bestHand == null && preflop?.stage == HandStage.PREFLOP,
            "no five-card rank on preflop")
        println("PokerMath scenarios passed: $checks")
    }
}
