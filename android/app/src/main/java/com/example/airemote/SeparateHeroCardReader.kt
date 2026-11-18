package com.example.airemote

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * T042 V85. A SECOND independent way of finding the two player's cards:
 * each white card is detected individually in the lower half of a captured
 * portrait frame. This does not assume a single joined white 2-card blob.
 *
 * Candidate detection only; even TWO correct-looking ranks/suits do not
 * establish source app, active seat, hand identity or authority to tap.
 */
object SeparateHeroCardReader {
    private fun white(p: Int): Boolean {
        val r=(p ushr 16) and 255
        val g=(p ushr 8) and 255
        val b=p and 255
        return min(r,min(g,b))>=210 && max(r,max(g,b))-min(r,min(g,b))<48
    }
    fun locate(r: VisionRaster): List<VisionCardBox> {
        if(r.width<320 || r.height<700 || r.width.toDouble()/r.height !in 0.32..0.80)
            return emptyList()
        val x0=(r.width*0.12).toInt()
        val x1=(r.width*0.88).toInt()
        val y0=(r.height*0.55).toInt()
        val y1=(r.height*0.95).toInt()
        val w=x1-x0
        val h=y1-y0
        val checked=BooleanArray(w*h)
        val queue=IntArray(w*h)
        val rects=mutableListOf<VisionCardBox>()
        for(y in 0 until h) for(x in 0 until w) {
            val first=y*w+x
            if(checked[first])continue
            checked[first]=true
            if(!white(r.rgb(x0+x,y0+y)))continue
            var head=0;var tail=1
            queue[0]=first
            var minX=x;var maxX=x;var minY=y;var maxY=y;var count=0
            while(head<tail){
                val p=queue[head++]
                val px=p%w;val py=p/w
                count++
                minX=min(minX,px);maxX=max(maxX,px)
                minY=min(minY,py);maxY=max(maxY,py)
                fun visit(nx:Int,ny:Int) {
                    if(nx !in 0 until w || ny !in 0 until h)return
                    val k=ny*w+nx
                    if(checked[k])return
                    checked[k]=true
                    if(white(r.rgb(x0+nx,y0+ny)))queue[tail++]=k
                }
                visit(px-1,py);visit(px+1,py);visit(px,py-1);visit(px,py+1)
            }
            val bw=maxX-minX+1
            val bh=maxY-minY+1
            if(bw.toDouble()/r.width !in 0.055..0.175 ||
               bh.toDouble()/r.height !in 0.038..0.145 ||
               bw.toDouble()/bh !in 0.42..0.99 ||
               count.toDouble()/(bw*bh)<0.62)continue
            val box=VisionCardBox(x0+minX,y0+minY,x0+maxX+1,y0+maxY+1)
            if(box.top.toDouble()/r.height in 0.55..0.89)rects.add(box)
        }
        if(rects.size !in 2..10)return emptyList()
        val pairs=mutableListOf<List<VisionCardBox>>()
        for (a in rects) for(b in rects){
            if(b.left<=a.left)continue
            if(abs(a.top-b.top)>max(5,(a.height*.18).toInt()) ||
                abs(a.width-b.width)>max(5,(a.width*.25).toInt()) ||
                abs(a.height-b.height)>max(5,(a.height*.20).toInt()))continue
            val distance=b.left-a.left
            if(distance.toDouble()/a.width !in 0.80..1.85)continue
            val mid=(a.left+b.right)/2.0/r.width
            if(mid !in 0.30..0.70)continue
            pairs.add(listOf(a,b))
        }
        // Multiple plausible pairs => unknown, not the nearest or brightest.
        return if(pairs.size==1)pairs.single() else emptyList()
    }

    /** Read-only partial labels even when a strict card candidate is absent. */
    fun preview(r: VisionRaster): List<String> =
        locate(r).map { CardGlyphChain.read(r,it).preview }

    fun read(r: VisionRaster): CardReading? {
        val pair=locate(r)
        if(pair.size!=2)return null
        val publicBoard=TableBoardCandidateSelector.choose(
            CardVisionCore.readVisibleCommunity(r),
            CenteredTableBoardReader.read(r))
        if(!publicBoard.complete && publicBoard.cards.isNotEmpty())return null
        val community=if(publicBoard.complete) publicBoard.cards.filterNotNull()
            else emptyList()
        val symbols=pair.map { CardGlyphChain.read(r,it) }
        if(symbols.any{it.code==null})return null
        val hole=symbols.mapNotNull{it.code}
        val minimum=symbols.minOf{it.quality}
        if(minimum<0.78 || !CardVisionCore.checkUnique(hole,community))return null
        return CardReading(hole,community,true,minimum,
            "SEPARATE_HERO_PAIR_UNVERIFIED",symbols.map{it.preview},publicBoard.glyphs)
    }
}
