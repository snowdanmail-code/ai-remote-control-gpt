package com.example.airemote

import kotlin.math.max

/**
 * A separate, read-only observation of the TWO small face-up card portraits
 * inside the top-left card-chip (not the large hero cards).
 *
 * Normalized 709x1536 reference from owner screenshots:
 * card 1 x=90..106, card 2 x=113..128; y=85..124.
 *
 * These are corroborating rank hints, never game-state/hand identity.
 */
object MiniHeroRankReader {
    private data class FractionalBox(
        val left: Double, val top: Double, val right: Double, val bottom: Double
    )
    private val boxes = listOf(
        FractionalBox(.126, .055, .150, .081),
        FractionalBox(.159, .055, .183, .081)
    )
    private fun white(argb: Int): Boolean {
        val r=(argb ushr 16) and 255
        val g=(argb ushr 8) and 255
        val b=argb and 255
        return minOf(r,g,b)>=207 && maxOf(r,g,b)-minOf(r,g,b)<43
    }
    /**
     * Returns either two rank hints, with nulls for uncertain faces, or an
     * empty list if the mini cards are absent. No suit is inferred here.
     */
    fun read(r: VisionRaster): List<Char?> {
        if (r.height < 700 || r.width < 320 ||
            r.width.toDouble()/r.height !in 0.32..0.80) return emptyList()
        val detected = mutableListOf<Char?>()
        for (f in boxes) {
            val x0=(r.width*f.left).toInt()
            val x1=(r.width*f.right).toInt()
            val y0=(r.height*f.top).toInt()
            val y1=(r.height*f.bottom).toInt()
            val w=x1-x0; val h=y1-y0
            if(w < 8 || h < 15 || x0 < 0 || y0 < 0 || x1 > r.width ||
                y1 > r.height) return emptyList()
            var whites=0
            for(y in y0 until y1)for(x in x0 until x1)
                if(white(r.rgb(x,y))) whites++
            // A blank/face-down top-left pill is NOT a hand.
            if (whites.toDouble()/(w*h) !in .28.. .91) return emptyList()

            // Small glyphs are resampled uniformly to let the SAME checked
            // rank classifiers inspect only their upper-left corners.
            val scale=5
            val newW=w*scale; val newH=h*scale
            val pixels=IntArray(newW*newH)
            for(y in 0 until newH)for(x in 0 until newW)
                pixels[y*newW+x]=r.rgb(x0+x/scale,y0+y/scale)
            val up=VisionRaster(newW,newH,pixels)
            val rank=CardVisionCore.classifyRank(up, VisionCardBox(0,0,newW,newH))
            detected.add(rank?.takeIf{it.similarity>=.93}?.rank)
        }
        return detected
    }
}

/** Read-only text parser. Text can only agree or disagree with a visual rank. */
object PreflopBottomRankText {
    private val headline=Regex("(?iu)(?:старшая\\s+рука|high\\s+card)\\s*[:：]?\\s*(.*)")
    private val card=Regex("(?iu)^(?:10|[2-9TJQKAТКДВ])$")
    private fun token(t: String): Char? = when(t.uppercase()) {
        "10", "T" -> 'T'
        "А", "A", "Т" -> 'A'
        "К", "K" -> 'K'
        "Д", "Q" -> 'Q'
        "В", "J" -> 'J'
        "2","3","4","5","6","7","8","9" -> t[0]
        else -> null
    }

    /** Positions must be genuinely within the bottom game summary line. */
    fun parse(lines: List<PositionedOcrLine>, allowPreflop: Boolean): List<Char>? {
        if (!allowPreflop) return null
        val values=lines.take(100).filter{
            it.box.valid() && it.box.centerY() in 895..960 &&
                it.box.centerX() in 240..810 && it.text.length <= 90
        }.mapNotNull { line ->
            val tail=headline.find(line.text)?.groupValues?.getOrNull(1) ?: return@mapNotNull null
            val parts=tail.trim().split(Regex("\\s+")).filter{it.isNotEmpty()}
            if(parts.size != 2 || parts.any{!card.matches(it)})return@mapNotNull null
            parts.mapNotNull(::token).takeIf{it.size==2}
        }
        // Multiple different OCR answers on one screen are not a vote.
        return values.distinct().singleOrNull()
    }
}

/**
 * Lower "Старшая рука" only assists rank diagnostics before the flop.
 * Once any 3+ board cards appear, remain CLOSED even if the board is missed
 * on later frames. Re-arm only on two distinct new-hand images showing
 * a different TWO-card mini pair while the public board is absent.
 */
class PreflopRankHintGate {
    private var epoch=-1L
    private var sequence=-1L
    private var postFlopSeen=false
    private var lastHand: List<Char>?=null
    private var nextHandCandidate: List<Char>?=null
    private var candidateFrames=0
    private var lastCandidateAt=-1L

    @Synchronized fun reset() {
        epoch=-1L
        sequence=-1L
        postFlopSeen=false
        lastHand=null
        nextHandCandidate=null
        candidateFrames=0
        lastCandidateAt=-1L
    }

    @Synchronized fun offer(captureEpoch:Long,frameSequence:Long,
                           boardCount:Int, miniPair:List<Char?>,
                           observedAtMs:Long):Boolean {
        if(captureEpoch<0L || frameSequence<=0L || observedAtMs<0L) {
            reset();return false
        }
        if(epoch!=captureEpoch) {
            reset()
            epoch=captureEpoch
        }
        if(frameSequence<=sequence) return !postFlopSeen && boardCount==0
        sequence=frameSequence

        val pair=miniPair.takeIf{it.size==2&&it.all{x->x!=null}}?.filterNotNull()
        if(!postFlopSeen && pair!=null)lastHand=pair

        if(boardCount in 3..5) {
            postFlopSeen=true
            nextHandCandidate=null
            candidateFrames=0
            return false
        }
        if(boardCount!=0)return false // unknown board is not proof of preflop
        if(!postFlopSeen)return true

        // Both visible mini faces must describe a different new hand, and be
        // stable on two non-duplicate images. No "board disappeared => new hand".
        if(pair==null || lastHand==null || pair==lastHand) {
            nextHandCandidate=null
            candidateFrames=0
            return false
        }
        val adjacent=nextHandCandidate==pair &&
            observedAtMs>=lastCandidateAt &&
            observedAtMs-lastCandidateAt in 400L..3500L
        candidateFrames=if(adjacent)candidateFrames+1 else 1
        nextHandCandidate=pair
        lastCandidateAt=observedAtMs
        if(candidateFrames<2)return false
        lastHand=pair
        postFlopSeen=false
        nextHandCandidate=null
        candidateFrames=0
        return true
    }

    @Synchronized fun active():Boolean = !postFlopSeen && epoch>=0L
}

/** A small diagnostic, not strategy state or a betting permission. */
data class PreflopRankHintSnapshot(
    val captureEpoch:Long,val frameSequence:Long,
    val observedAtMs:Long,val allowed:Boolean,
    val miniRanks: List<Char?> = emptyList(),
    val bottomTextRanks: List<Char>? = null
)
