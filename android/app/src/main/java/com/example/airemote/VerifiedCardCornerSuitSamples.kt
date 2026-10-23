package com.example.airemote

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A second independent SUIT reader from the small upper-left suit mark
 * immediately below the card rank, rather than the large suit in card center.
 *
 * 8 distinct 24x24 suit silhouettes, 4 board and 4 hero samples, derived
 * exclusively from operator-labeled card crops. NO personal screen data.
 * Classification remains conservative and does not authorize any game action.
 */
object VerifiedCardCornerSuitSamples {
    private const val SIZE = 24
    private data class Sample(val suit: Char, val mask: IntArray)
    private val raw = """
h|0000000f83e03fcff87ffff87ffffc7ffffc7ffffc7ffffc7ffffc7ffffc7ffffc7ffff83ffff83ffff01ffff00fffe007ffc007ffc001ff0000fe00007c00003800000000000000
h|0000000601803fcff03ffff83ffff83ffff83ffff83ffff83ffff83ffff83ffff83ffff83ffff03ffff03ffff01fffc01fff8007ff0003fe0003fe0001f80000f800000000000000
d|000000003000007c0000fe0001fe0003ff0003ff8007ffc00fffe01ffff03ffff83ffff83ffff81ffff00fffe007ffc007ffc003ff8001ff0000fe00007c00003800000000000000
d|000000003c00007e0000ff0001ff0001ff0003ff800fffc01ffff01ffff83ffffc3ffff83ffff83ffff81ffff00fffc003ff8001ff8000ff0000ff00007e00003c00000000000000
c|000000003c00007e0000fe0000fe0000fe0000fe00007e00003c001f38f83fb9f83ffffc3ffffc3ffffc3fb9f81e38f0003800003800003c00007c00007e0000ff00000000000000
c|00000000780001fc0001fc0001fc0001fc0001fc0001f8000070c01e33f01ffff01ffff81ffff81e37f01c31e000300000700000700000780001f80001fc0003fc00000000000000
s|000000001000003800007c0000fe0001ff0003ff8007ffc00fffe01ffff03ffff83ffff83ffff83ffff83ffff83ffff81ffff00fffe0003800003c00007c0000fe00000000000000
s|000000001800003c00007e0000ff8003ffc003ffc007ffe00ffff01ffff01ffff83ffff83ffff83ffff81ffff81ffff007dbe007dbe0003800003c00007e00007e00000000000000
    """.trimIndent()
    private val templates: List<Sample> by lazy {
        raw.lines().filter { '|' in it }.map { line ->
            val bits=line.substringAfter('|').trim()
            require(bits.length==SIZE*6)
            Sample(line[0],IntArray(SIZE){ y ->
                bits.substring(y*6,y*6+6).toInt(16)
            })
        }
    }
    val templateCount: Int get() = templates.size

    private fun ink(p: Int): Boolean {
        val red=(p ushr 16) and 255
        val green=(p ushr 8) and 255
        val blue=p and 255
        return (red<145 && green<145 && blue<145) ||
            (red>95 && red>green*1.5 && red>blue*1.45)
    }
    private fun red(p: Int): Boolean {
        val r=(p ushr 16) and 255
        val g=(p ushr 8) and 255
        val b=p and 255
        return r>95 && r>g*1.5 && r>b*1.45
    }
    internal data class Normalized(val mask:IntArray,val red:Boolean)
    internal fun cornerMask(r: VisionRaster,card:VisionCardBox): Normalized? {
        val x0=card.left+max(2,card.width*3/100)
        val x1=card.left+card.width*40/100
        val y0=card.top+card.height*27/100
        val y1=card.top+card.height*63/100
        val w=x1-x0;val h=y1-y0
        if(w<12 || h<16 || x0<0 || y0<0 || x1>r.width || y1>r.height)return null
        val active=BooleanArray(w*h){i -> ink(r.rgb(x0+i%w,y0+i/w))}
        val visited=BooleanArray(w*h)
        val queue=IntArray(w*h)
        var winner=IntArray(0)
        for(i in active.indices) {
            if(visited[i] || !active[i])continue
            visited[i]=true
            var head=0;var end=1;queue[0]=i
            while(head<end){
                val p=queue[head++];val px=p%w;val py=p/w
                for(dy in -1..1)for(dx in -1..1){
                    if(dx==0 && dy==0)continue
                    val xx=px+dx;val yy=py+dy
                    if(xx !in 0 until w || yy !in 0 until h)continue
                    val k=yy*w+xx
                    if(!visited[k] && active[k]){visited[k]=true;queue[end++]=k}
                }
            }
            if(end>winner.size)winner=queue.copyOf(end)
        }
        if(winner.size<55)return null
        val minX=winner.minOf { it%w };val maxX=winner.maxOf { it%w }
        val minY=winner.minOf { it/w };val maxY=winner.maxOf { it/w }
        val bw=maxX-minX+1;val bh=maxY-minY+1
        if(bw<10 || bh<12)return null
        val normalized=IntArray(SIZE)
        val members=BooleanArray(w*h)
        for(i in winner)members[i]=true
        val scale=min(21.0/bw,21.0/bh)
        val nw=(bw*scale).roundToInt().coerceIn(1,21)
        val nh=(bh*scale).roundToInt().coerceIn(1,21)
        val ox=(SIZE-nw)/2;val oy=(SIZE-nh)/2
        for(y in 0 until nh)for(x in 0 until nw){
            val srcX=minX+(((x+.5)*bw/nw)-.5).roundToInt().coerceIn(0,bw-1)
            val srcY=minY+(((y+.5)*bh/nh)-.5).roundToInt().coerceIn(0,bh-1)
            if(members[srcY*w+srcX])
                normalized[y+oy]=normalized[y+oy] or (1 shl (23-x-ox))
        }
        val redPixels=winner.count { red(r.rgb(x0+it%w,y0+it/w)) }
        return Normalized(normalized,redPixels>winner.size/2)
    }

    fun classify(r:VisionRaster,card:VisionCardBox):SuitCandidate? {
        val obs=cornerMask(r,card)?:return null
        return classifyMask(obs.mask,obs.red)
    }

    internal fun classifyMask(mask:IntArray,isRed:Boolean):SuitCandidate? {
        if(mask.size!=SIZE || mask.all { it==0 })return null
        val ranked=templates
            .filter { (it.suit=='h'||it.suit=='d')==isRed }
            .groupBy{it.suit}.mapValues{(_,sample)->
                sample.minOf { target ->
                    (0 until SIZE).sumOf { y ->
                        Integer.bitCount((mask[y] xor target.mask[y]) and 0xFFFFFF)
                    }.toDouble()/(SIZE*SIZE)
                }
            }.entries.sortedBy{it.value}
        val first=ranked.firstOrNull()?:return null
        val second=ranked.getOrNull(1)?.value?:1.0
        // Bounds verified on 26 operator-visible cards; NOT confidence calibration.
        return if(first.value<=0.075 && second-first.value>=0.07)
            SuitCandidate(first.key,1.0-first.value) else null
    }

    internal fun selfCheck(): Int = templates.count {
        classifyMask(it.mask,it.suit=='h'||it.suit=='d')?.suit==it.suit
    }
}
