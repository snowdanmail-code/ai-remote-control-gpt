package com.example.airemote

import kotlin.random.Random

/**
 * Optional strategy estimate against uniformly sampled UNKNOWN opponent cards.
 * This is a poker-model assumption, NOT a claim to know opponent hole cards,
 * player ranges or actual showdown probability. No action permissions here.
 *
 * Each sample deals a legal, non-overlapping river and separate two-card hands.
 * A tie shares the pot between all equal-highest hands. Samples are bounded to
 * keep phone CPU predictable; never run this while holding an Android STOP lock.
 */
object UniformUnknownPokerEquity {
    fun compute(hero: List<PokerCard>, board: List<PokerCard>,
                opponents: Int, samples: Int = 300, seed: Int = 11): PokerMath.EquityResult {
        require(hero.size == 2 && board.size in listOf(0, 3, 4, 5))
        require(opponents in 1..5 && samples in 200..2000)
        val fixed = hero + board
        require(fixed.toSet().size == fixed.size) { "Duplicate card" }
        val needed = 5 - board.size + 2 * opponents
        val remaining = (2..14).flatMap { r -> "cdhs".map { PokerCard(r, it) } }
            .filterNot { it in fixed.toSet() }
        require(remaining.size >= needed)
        val rng=Random(seed)
        var win=0
        var tie=0
        var lose=0
        var potShare=0.0
        repeat(samples) {
            val deck=remaining.toMutableList()
            for (i in 0 until needed) {
                val index=rng.nextInt(i, deck.size)
                val swap=deck[i]; deck[i]=deck[index]; deck[index]=swap
            }
            val completion=5-board.size
            val public=board+deck.take(completion)
            val ourValue=PokerMath.evaluate(hero+public)
            var equallyBest=1
            var surpassed=false
            for (i in 0 until opponents) {
                val start=completion+i*2
                val rival=PokerMath.evaluate(public+deck.subList(start,start+2))
                when {
                    rival>ourValue -> surpassed=true
                    rival==ourValue -> equallyBest++
                }
            }
            if (surpassed) lose++
            else {
                if (equallyBest==1) win++ else tie++
                potShare += 1.0/equallyBest
            }
        }
        return PokerMath.EquityResult(win.toDouble()/samples,
            tie.toDouble()/samples,lose.toDouble()/samples,potShare/samples,
            samples,false)
    }
}
