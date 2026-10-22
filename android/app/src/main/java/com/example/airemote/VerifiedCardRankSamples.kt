package com.example.airemote

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * User-verified glyph samples extracted from cropped 1win cards in 10 real
 * 709x1536 owner screenshots. No names, avatars, balances or screenshots
 * retained in the runtime database. 29 labeled card instances, 23 distinct
 * glyph masks. Layout normalized to 540x1170 Android detection raster.
 * This does not train a neural network or independently prove correct cards.
 */
object VerifiedCardRankSamples {
    private const val SIZE = 24
    private data class Stored(val rank: Char, val mask: IntArray)
    private val raw = """
K|00000007c3c00fc7e007c7e0038780038700038700038f0003be0003fc0003fc0003fc0003fc0003fe0003ce00038f0003870003870003878003838007c7e00fc7e007c3e0000000
7|0000000ffff00ffff00f00f00f00f00f01f00401e00003c00003c00003c0000700000700001e00001e00001e00001e00003c00003c00003c00007800007800007800007800000000
8|000000003c0000ff0003ff8003c3c00781e00781e00700e00701c003c3c003ffc003ff8003ffc00781e00700e00e00e00e00e00e00e00700e00781e003ffc001ff80003c00000000
J|000000001fe0000fe00003c00003c00003c00003c00003c00003c00003c00003c00003c00003c00003c00003c00e03c00e03c00e07800f8f800f8f8007ff0003fc00007000000000
6|000000003c0000ff0001ffc003c1c007808007808007000007ff0007ff8007c3c00700e00700e00600600600600700600700e00700e00700e00381c001ffc000ff00003c00000000
9|000000003c0000ff0001ff8003c3800701c00701c00701c00700c00601c00701c00701c00383c003ffc001ffc0007fc00001c00001c001038003878003ff0001fe00003c00000000
Q|00000000380000fe0000fe0001830003018003018003018003018003018003018003018007e18007f98003ff80031f80038f8001c78001ffe0007c00000000000000001e00000000
T|0000003c0f803c3fe03c7ff03cf8f03cf0703cf0783cf0783ce0783ce0783ce0783ce0783cf0783cf0783ce0783ce0783cf0703cf0703cf0703cf9f03c7ff01c3fe01c0f80000000
A|000000003800003c00007c00007c00007c0000fe0000ee0000ee0000e60000c60000c60000c60000ff0000ff0001ff0001830001830007c7e007c7c0000000000000001e00000000
9|000000003800007e0000ff0001830003838003018003018003018003838003838003838000ff80007f8000038000038001830001c70001fe00007c00000000000000000e00000000
5|00000003ff8007ff8007ff8007000007000007f80007ff0007ff800783c00001e00000e00000e00000e00000e00000e00000e00e01e00f01e007c7c003ff8001ff00007c00000000
J|000000000ff0000ff00003c00003c00003c00003c00003c00003c00003c00003c00003c00003c00003c00003c00e03c00e03c00f83c007c7c007c7c007ff8003fe00003800000000
K|0000000787c00fc7e00f87c0070780070f00070f00070e00073c0007fc0007f80007f80007fc0007fc0007be00078e000787000787000787000787800fc7c00fcfe00787c0000000
Q|000000007e0001ffc003c3c003c3c003c3c00381c00381c00381c00381c00381c00381c00381c007c1c00ff1e007ffe003ffc003cfc003c7e003c7e003fff001fff0003c00000000
A|000000003800003c00007c00007c00007c00007c00007e0001fe0001ee0001e60001e60001e60001e78003e78003ff8003ff8003e78003c3c003c3c00fc7e00fc7f00fc7e0000000
Q|000000007e0003ff800383c00783c00783c00783c00783c00783c00783c00783c00783c00783c00783c00ff3c00ffbc007bfc0079fc007cfc007cfc003fff001fff0003800000000
9|000000003c0000ff0001ff8001c3c00381c00381c00300c00700e00700e00700e00381e003c3e001ffe000ffe0007fc00001c00001c00101c001c38001ff8000ff00003c00000000
8|000000007e0003ffc007c3c00781e00781e00601e00781e007c3e003ffc003ffc007ffe007ffe00781e00e00700e00700e00700e007007c3e007c3e003ffc001ff80001c00000000
4|000000000780000f80001f80001f80001f80003f80007f8000778001e78003c78007c78007c7800787800fcfe00fffe00fffe0000fc0000780000780000fc0000fc0000fc0000000
3|00000007ffc007ffe00fffe00f07c0060f00060f00003e00007e0000ff0000ffc00003c00000e00000e00000e00000e00600e00600e00701e00783c003ff8001ff00007c00000000
8|000000007c0001ff0003ff8007c7c00701e00701e00701e00781e007c7c003ff8003ff8007ffc00f83e00f00e00e00f00e00f00e00f00f01e007c3e007ffc001ff80007c00000000
K|0000000fc7e00feff00fc7e0078780038780038f00039e00039c0003fc0003f80003fc0003fc0003de00038e00038f000787800787800783c00fc7e00feff00fc7e0000000
T|000000380f003c3fc03c7fe03cf8f03cf0703cf0783ce0781ce0781ce0781cf0783ce0783ce0783cf0783cf0783cf0783cf0703cf0703cf9f03c7fe03c3fe01c0f00000000
    """.trimIndent()

    private val templates: List<Stored> by lazy {
        raw.lines().filter { '|' in it }.map { row ->
            val key = row.substringBefore('|').first()
            val bits = row.substringAfter('|').trim()
            require(bits.length == 24 * 6)
            Stored(key, IntArray(24) { y ->
                bits.substring(y*6, y*6+6).toInt(16)
            })
        }
    }
    val sampleCount: Int get() = templates.size
    /** Self-check only; NOT independent field accuracy. */
    internal fun sampleSanityChecks(): Int = templates.count {
        classifyMask(it.mask)?.rank == it.rank
    }

    private fun ink(p: Int): Boolean {
        val r = (p ushr 16) and 255
        val g = (p ushr 8) and 255
        val b = p and 255
        return r < 145 && g < 145 && b < 145 ||
            r > 95 && r > g*1.5 && r > b*1.45
    }

    // Crop ONLY rank glyph: stop before the small suit printed immediately
    // below it. The legacy crop used the first 34% of card height and often
    // included part of that suit, corrupting the rank mask.
    internal fun rankMask(r: VisionRaster, card: VisionCardBox): IntArray? {
        val x0=card.left+max(2,card.width*3/100)
        val x1=card.left+card.width*55/100
        val y0=card.top+max(2,card.height*2/100)
        val y1=card.top+(card.height*305/1000)
        val w=x1-x0; val h=y1-y0
        if(w<9 || h<12 || x0<0 || y0<0 || x1>r.width || y1>r.height)return null
        val bitmap=BooleanArray(w*h){i->ink(r.rgb(x0+i%w,y0+i/w))}
        val counts=IntArray(h){y->(0 until w).count{x->bitmap[y*w+x]}}
        val start=(0 until h).firstOrNull{counts[it]>=3} ?:return null
        var end=(0 until h).lastOrNull{counts[it]>=3}?.plus(1) ?:return null
        for(y in start+11 until h-2) {
            if(counts[y]<=2 && counts[y+1]<=2 && counts[y+2]<=2) {
                end=y
                break
            }
        }
        var minX=w;var maxX=-1;var minY=h;var maxY=-1
        for(y in start until end)for(x in 0 until w)if(bitmap[y*w+x]){
            minX=min(minX,x);maxX=max(maxX,x)
            minY=min(minY,y);maxY=max(maxY,y)
        }
        if(maxX<minX || maxY<minY)return null
        val tw=maxX-minX+1; val th=maxY-minY+1
        if(tw<4 || th<8)return null
        val scale=min(22.0/tw,22.0/th)
        val nw=(tw*scale).roundToInt().coerceIn(1,22)
        val nh=(th*scale).roundToInt().coerceIn(1,22)
        val ox=(SIZE-nw)/2; val oy=(SIZE-nh)/2
        val result=IntArray(SIZE)
        for(y in 0 until nh)for(x in 0 until nw){
            val bx=(minX+(((x+.5)*tw/nw)-.5).roundToInt().coerceIn(0,tw-1))
            val by=(minY+(((y+.5)*th/nh)-.5).roundToInt().coerceIn(0,th-1))
            if(bitmap[by*w+bx])result[y+oy]=result[y+oy] or (1 shl (23-x-ox))
        }
        return result
    }

    fun classify(r: VisionRaster, card: VisionCardBox): RankCandidate? {
        val mask=rankMask(r,card)?:return null
        return classifyMask(mask)
    }

    internal fun classifyMask(mask: IntArray): RankCandidate? {
        if(mask.size!=24 || mask.all { it==0 }) return null
        val best=templates.groupBy { it.rank }.mapValues { (_, collection) ->
            collection.minOf { template ->
                (0 until SIZE).sumOf { y ->
                    Integer.bitCount((mask[y] xor template.mask[y]) and 0xFFFFFF)
                }.toDouble() / 576.0
            }
        }.entries.sortedBy { it.value }
        val first=best.firstOrNull() ?:return null
        val runnerUp=best.getOrNull(1)?.value ?:1.0
        // Refuse weak/ambiguous near-matches; this is not a calibrated probability.
        return if(first.value<=0.15 && runnerUp-first.value>=0.055)
            RankCandidate(first.key,1.0-first.value) else null
    }
}
