package com.example.airemote

import kotlin.math.min
import kotlin.random.Random

/**
 * Deterministic, side-effect-free Texas Hold'em mathematics.
 * No OCR, no strategy, no Android gestures, no permissions. Bad input fails closed.
 * Chips are integer units, never floating-point monetary amounts.
 */
data class PokerCard(val rank: Int, val suit: Char) {
    init { require(rank in 2..14 && suit in "cdhs") { "Invalid card" } }
    override fun toString(): String = "${"--23456789TJQKA"[rank]}$suit"
    companion object {
        fun parse(text: String): PokerCard {
            val value = text.trim()
            val normalized = if (value.length == 3 && value.startsWith("10")) "T${value[2]}" else value
            require(normalized.length == 2) { "Expected two-character card" }
            val rank = "23456789TJQKA".indexOf(normalized[0].uppercaseChar()) + 2
            val suit = normalized[1].lowercaseChar()
            return PokerCard(rank, suit)
        }
    }
}

enum class PokerHandClass { HIGH_CARD, ONE_PAIR, TWO_PAIR, THREE_OF_A_KIND, STRAIGHT,
    FLUSH, FULL_HOUSE, FOUR_OF_A_KIND, STRAIGHT_FLUSH }

/** Rank ordering: category first, then lexicographically highest category-specific kickers. */
data class PokerHandValue(val category: PokerHandClass, val tiebreak: List<Int>) : Comparable<PokerHandValue> {
    override fun compareTo(other: PokerHandValue): Int {
        val categoryDifference = category.ordinal.compareTo(other.category.ordinal)
        if (categoryDifference != 0) return categoryDifference
        for (i in 0 until min(tiebreak.size, other.tiebreak.size)) {
            val diff = tiebreak[i].compareTo(other.tiebreak[i])
            if (diff != 0) return diff
        }
        return tiebreak.size.compareTo(other.tiebreak.size)
    }
}

object PokerMath {
    private val fullDeck: List<PokerCard> = (2..14).flatMap { rank ->
        "cdhs".map { suit -> PokerCard(rank, suit) }
    }

    private fun ensureUnique(cards: List<PokerCard>) {
        require(cards.size == cards.toSet().size) { "Duplicate physical card" }
    }

    private fun straightHigh(ranks: Collection<Int>): Int? {
        val unique = ranks.toSet()
        for (high in 14 downTo 5) {
            if ((0..4).all { offset ->
                    val rank = high - offset
                    (if (rank == 1) 14 else rank) in unique
                }) return high
        }
        return null
    }

    /** All five cards used; ace-low wheel returns straight high 5. */
    fun evaluateFive(cards: List<PokerCard>): PokerHandValue {
        require(cards.size == 5) { "Five cards required" }
        ensureUnique(cards)
        val ranks = cards.map { it.rank }
        val groups = ranks.groupingBy { it }.eachCount()
        val sorted = groups.entries.sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }
            .thenByDescending { it.key })
        val flush = cards.all { it.suit == cards[0].suit }
        val straight = straightHigh(ranks)
        if (flush && straight != null) return PokerHandValue(PokerHandClass.STRAIGHT_FLUSH, listOf(straight))
        if (sorted[0].value == 4) return PokerHandValue(PokerHandClass.FOUR_OF_A_KIND,
            listOf(sorted[0].key, sorted[1].key))
        if (sorted[0].value == 3 && sorted[1].value == 2) return PokerHandValue(PokerHandClass.FULL_HOUSE,
            listOf(sorted[0].key, sorted[1].key))
        if (flush) return PokerHandValue(PokerHandClass.FLUSH, ranks.sortedDescending())
        if (straight != null) return PokerHandValue(PokerHandClass.STRAIGHT, listOf(straight))
        if (sorted[0].value == 3) return PokerHandValue(PokerHandClass.THREE_OF_A_KIND,
            listOf(sorted[0].key) + sorted.drop(1).map { it.key }.sortedDescending())
        if (sorted[0].value == 2 && sorted[1].value == 2) {
            val pairs = sorted.take(2).map { it.key }.sortedDescending()
            return PokerHandValue(PokerHandClass.TWO_PAIR, pairs + sorted[2].key)
        }
        if (sorted[0].value == 2) return PokerHandValue(PokerHandClass.ONE_PAIR,
            listOf(sorted[0].key) + sorted.drop(1).map { it.key }.sortedDescending())
        return PokerHandValue(PokerHandClass.HIGH_CARD, ranks.sortedDescending())
    }

    /** Pick best five of 5, 6 or 7; rejects impossible duplicate cards. */
    fun evaluate(cards: List<PokerCard>): PokerHandValue {
        require(cards.size in 5..7) { "Five to seven cards required" }
        ensureUnique(cards)
        var best: PokerHandValue? = null
        for (a in 0 until cards.size - 4)
            for (b in a + 1 until cards.size - 3)
                for (c in b + 1 until cards.size - 2)
                    for (d in c + 1 until cards.size - 1)
                        for (e in d + 1 until cards.size) {
                            val candidate = evaluateFive(listOf(cards[a], cards[b], cards[c], cards[d], cards[e]))
                            if (best == null || candidate > best) best = candidate
                        }
        return requireNotNull(best)
    }

    fun potOddsRequiredEquity(potBeforeCall: Long, call: Long): Double {
        require(potBeforeCall >= 0 && call >= 0) { "Negative pot or call" }
        if (call == 0L) return 0.0
        return call.toDouble() / (potBeforeCall.toDouble() + call.toDouble())
    }

    fun effectiveStack(heroStack: Long, opponentStack: Long): Long {
        require(heroStack >= 0 && opponentStack >= 0)
        return min(heroStack, opponentStack)
    }

    data class RaiseBounds(val fullRaiseMinimumTo: Long?, val maximumTo: Long?,
                           val shortAllInTo: Long?)

    /**
     * No-limit bounds in 'raise-to' totals for one betting street.
     * A short all-in does NOT reopen action; caller must independently verify right to raise.
     */
    fun raiseBounds(currentBet: Long, playerCommitted: Long, stackBehind: Long,
                    lastFullRaiseSize: Long): RaiseBounds {
        require(currentBet >= 0 && playerCommitted >= 0 && stackBehind >= 0 && lastFullRaiseSize > 0)
        require(playerCommitted <= currentBet) { "Invalid street contribution" }
        val maximumTo = Math.addExact(playerCommitted, stackBehind)
        if (maximumTo <= currentBet) return RaiseBounds(null, null, null)
        val minimumTo = Math.addExact(currentBet, lastFullRaiseSize)
        return if (maximumTo >= minimumTo) RaiseBounds(minimumTo, maximumTo, null)
               else RaiseBounds(null, maximumTo, maximumTo)
    }

    data class PotLayer(val amount: Long, val eligiblePlayers: Set<String>,
                        val contributionCap: Long)
    data class SidePotResult(val pots: List<PotLayer>, val uncalledReturns: Map<String, Long>) {
        val contestedTotal: Long get() = pots.fold(0L) { acc, p -> Math.addExact(acc, p.amount) }
    }

    /** Folded chips stay in pots, but folded players can never win them. */
    fun sidePots(contributions: Map<String, Long>, foldedPlayers: Set<String>): SidePotResult {
        require(contributions.isNotEmpty() && contributions.keys.none { it.isBlank() })
        require(contributions.values.all { it >= 0 })
        require(foldedPlayers.all { it in contributions })
        val levels = contributions.values.filter { it > 0 }.distinct().sorted()
        var previous = 0L
        val pots = mutableListOf<PotLayer>()
        val returns = mutableMapOf<String, Long>()
        for (level in levels) {
            val funders = contributions.filterValues { it >= level }.keys
            val amount = Math.multiplyExact(level - previous, funders.size.toLong())
            if (funders.size == 1) {
                // Unmatched overbet returned to the sole contributor, not included in any pot.
                val sole = funders.single()
                require(sole !in foldedPlayers) { "Folded player cannot have an unmatched return" }
                returns[sole] = Math.addExact(returns[sole] ?: 0L, amount)
            } else {
                val eligible = funders.filterNot { it in foldedPlayers }.toSet()
                require(eligible.isNotEmpty()) { "No eligible winner for pot layer" }
                pots.add(PotLayer(amount, eligible, level))
            }
            previous = level
        }
        // Every chip is in a pot or refunded. No silent loss, even on folded contributions.
        val inputTotal = contributions.values.fold(0L, Math::addExact)
        val accounted = Math.addExact(pots.fold(0L) { acc, p -> Math.addExact(acc, p.amount) },
            returns.values.fold(0L, Math::addExact))
        check(inputTotal == accounted)
        return SidePotResult(pots, returns)
    }

    data class ShowdownSettlement(val payouts: Map<String, Long>,
                                  val layerWinners: List<Set<String>>,
                                  val uncalledReturns: Map<String, Long>)

    /**
     * Settle fully exposed showdown using final board and dealer-relative chip-award order.
     * The caller must provide the official odd-chip seat order; absent that order,
     * settlement is rejected rather than selecting an arbitrary split winner.
     * This is an offline calculation, never an action authorization.
     */
    fun settleShowdown(contributions: Map<String, Long>, foldedPlayers: Set<String>,
                       knownHands: Map<String, List<PokerCard>>, finalBoard: List<PokerCard>,
                       oddChipPriority: List<String>): ShowdownSettlement {
        require(finalBoard.size == 5) { "Five board cards required" }
        require(oddChipPriority.size == contributions.size &&
            oddChipPriority.toSet() == contributions.keys) { "Explicit dealer-relative chip order required" }
        require(knownHands.keys == contributions.keys - foldedPlayers) {
            "All and only eligible players must show two hole cards" }
        require(knownHands.values.all { it.size == 2 })
        ensureUnique(finalBoard + knownHands.values.flatten())
        val layers = sidePots(contributions, foldedPlayers)
        val values = knownHands.mapValues { (_, hole) -> evaluate(hole + finalBoard) }
        val payouts = contributions.mapValues { 0L }.toMutableMap()
        for ((player, amount) in layers.uncalledReturns)
            payouts[player] = Math.addExact(payouts.getValue(player), amount)
        val winnersByLayer = mutableListOf<Set<String>>()
        for (layer in layers.pots) {
            val top = layer.eligiblePlayers.maxOf { values.getValue(it) }
            val winners = layer.eligiblePlayers.filter { values.getValue(it) == top }.toSet()
            winnersByLayer.add(winners)
            val base = layer.amount / winners.size
            val leftover = (layer.amount % winners.size).toInt()
            for (player in winners)
                payouts[player] = Math.addExact(payouts.getValue(player), base)
            // Odd chips go to winner(s) closest left of the button, by supplied order.
            for (player in oddChipPriority.filter { it in winners }.take(leftover))
                payouts[player] = Math.addExact(payouts.getValue(player), 1L)
        }
        val paid = payouts.values.fold(0L, Math::addExact)
        val contributed = contributions.values.fold(0L, Math::addExact)
        check(paid == contributed) { "Chip conservation violated" }
        return ShowdownSettlement(payouts, winnersByLayer, layers.uncalledReturns)
    }

    data class EquityResult(val winProbability: Double, val tieProbability: Double,
                            val lossProbability: Double, val potShareEquity: Double,
                            val runouts: Int, val exact: Boolean)

    /**
     * Equity vs *known* opponent hole cards. Monte Carlo for huge runout spaces;
     * never invent unobserved opponents' ranges. The tie share is 1 / number of winners.
     */
    fun equityVsKnownHands(hero: List<PokerCard>, opponents: List<List<PokerCard>>,
                           board: List<PokerCard> = emptyList(), samples: Int = 2000,
                           seed: Int = 11, exactLimit: Int = 50_000): EquityResult {
        require(hero.size == 2 && opponents.isNotEmpty() && opponents.all { it.size == 2 })
        require(board.size in 0..5 && samples in 1..100_000 && exactLimit >= 1)
        val known = hero + opponents.flatten() + board
        ensureUnique(known)
        require(known.size <= 52)
        val remaining = fullDeck.filterNot { it in known.toSet() }
        val missing = 5 - board.size
        require(remaining.size >= missing)
        fun binomial(n: Int, k: Int): Long {
            var result = 1L
            for (i in 1..k) result = result * (n - i + 1) / i
            return result
        }
        val totalRunouts = binomial(remaining.size, missing)
        val exact = totalRunouts <= exactLimit
        var wins = 0
        var ties = 0
        var losses = 0
        var share = 0.0
        var count = 0
        fun score(runout: List<PokerCard>) {
            val community = board + runout
            val ourValue = evaluate(hero + community)
            val rivals = opponents.map { evaluate(it + community) }
            val better = rivals.any { it > ourValue }
            if (better) losses++ else {
                val equal = rivals.count { it == ourValue }
                if (equal == 0) wins++ else ties++
                share += 1.0 / (equal + 1)
            }
            count++
        }
        if (exact) {
            val runout = ArrayList<PokerCard>(missing)
            fun enumerate(start: Int) {
                if (runout.size == missing) { score(runout); return }
                val needed = missing - runout.size
                for (index in start..remaining.size - needed) {
                    runout.add(remaining[index])
                    enumerate(index + 1)
                    runout.removeAt(runout.lastIndex)
                }
            }
            enumerate(0)
        } else {
            val random = Random(seed)
            repeat(samples) {
                val deck = remaining.toMutableList()
                for (i in 0 until missing) {
                    val j = random.nextInt(i, deck.size)
                    val item = deck[i]; deck[i] = deck[j]; deck[j] = item
                }
                score(deck.take(missing))
            }
        }
        check(count > 0)
        return EquityResult(wins.toDouble() / count, ties.toDouble() / count,
            losses.toDouble() / count, share / count, count, exact)
    }
}
