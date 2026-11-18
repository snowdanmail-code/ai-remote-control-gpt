package com.example.airemote

/**
 * Operator-only rank corroboration. Suit comes from the visually read large
 * card, NOT a guess. Never feeds cardConsensus or a poker action.
 *
 * The bottom "Старшая рука" text is an UNORDERED two-rank summary.
 * It is valid strictly before the flop, with fresh capture provenance.
 */
object PreflopRankFusion {
    private val rankLabels=mapOf('A' to "Т",'K' to "К",'Q' to "Д",'J' to "В",'T' to "10")
    private val valid="23456789TJQKA"
    private val suits=setOf("♥","♦","♠","♣")
    private fun label(c:Char)=rankLabels[c]?:c.toString()
    private fun glyphParts(s:String):Pair<Char?,String?>{
        val bits=s.trim().split(Regex("\\s+"))
        if(bits.size!=2) return null to null
        val rank=when(bits[0]){
            "10"->'T';"Т"->'A';"К"->'K';"Д"->'Q';"В"->'J'
            else -> bits[0].singleOrNull()?.takeIf{it in '2'..'9'}
        }
        return rank to bits[1].takeIf{it in suits}
    }
    private fun sameRanks(a:List<Char>,b:List<Char>) =
        a.size==2 && b.size==2 && a.sorted()==b.sorted()

    fun preview(reading:CardReading,mini:List<Char?>,
                bottom:List<Char>?,beforeFlop:Boolean):String {
        if(reading.hole.isEmpty() && reading.holeGlyphs.isEmpty() &&
            mini.none{it!=null}) return "ИЩУ ДВЕ КАРТЫ"
        val n=maxOf(reading.hole.size,reading.holeGlyphs.size,2)
        val visual=(0 until n).map{i->
            val code=reading.hole.getOrNull(i)
            val fallback=reading.holeGlyphs.getOrNull(i)?.trim().orEmpty()
            if(code!=null) {
                val translated=VisibleCardOverlayText.readable(code)
                Triple(code[0],translated.substringAfter(' ',"").trim(),translated)
            } else {
                val (rank,suit)=glyphParts(fallback)
                Triple(rank,suit,if(fallback.isBlank()||fallback=="? ?")
                    "НЕ РАСПОЗНАНО" else fallback)
            }
        }
        val miniValid=mini.size==2 && mini.all{it!=null && it in valid}
        val minipair=if(miniValid)mini.filterNotNull() else emptyList()
        val caption=bottom?.takeIf{
            beforeFlop && reading.community.isEmpty() &&
                it.size==2 && it.all{r->r in valid}
        }
        val miniContradiction=miniValid && visual.indices.any { i ->
            visual[i].first != null && mini.getOrNull(i) != visual[i].first
        }
        val captionContradiction=caption != null && visual.any {
            it.first != null && !caption.contains(it.first)
        }
        val safeCaption=if(captionContradiction || miniContradiction) null else caption
        val miniAgrees=safeCaption!=null && miniValid &&
            !miniContradiction && sameRanks(safeCaption,minipair)
        val result=visual.mapIndexed{i,v->
            val (seenRank,seenSuit,shown)=v
            if(seenRank!=null || seenSuit==null)return@mapIndexed shown

            // MINI alone gives only a provisional operator label.
            val miniRank=if(miniContradiction) null else mini.getOrNull(i)?.takeIf{it in valid}
            val otherSeen=visual.getOrNull(1-i)?.first
            val bottomOnly=if(safeCaption!=null && otherSeen!=null &&
                safeCaption.contains(otherSeen)) {
                val remaining=safeCaption.toMutableList()
                remaining.remove(otherSeen)
                remaining.singleOrNull()
            }else null
            val candidate=when{
                miniRank!=null && safeCaption!=null && !miniAgrees -> null
                miniRank!=null -> miniRank
                bottomOnly!=null -> bottomOnly
                else -> null
            }
            if(candidate==null) shown else label(candidate)+" "+seenSuit+"*"
        }
        val recovered=result.any{it.endsWith("*")}
        val evidences=when{
            miniAgrees->"МИНИ + НИЗ"
            recovered && miniValid->"МИНИ"
            recovered && safeCaption!=null->"НИЗ ДО ФЛОПА"
            else -> ""
        }
        return result.joinToString("  ·  ") +
            (if(recovered && evidences.isNotEmpty())
                "  (*"+evidences+", ПРЕДВАРИТЕЛЬНО)" else "")
    }
}
