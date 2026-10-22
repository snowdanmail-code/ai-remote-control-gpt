package com.example.airemote

object VisibleCardOverlayScenarios {
    @JvmStatic fun main(args:Array<String>){
        var n=0
        fun ok(v:Boolean,s:String){check(v){s};n++}
        ok(VisibleCardOverlayText.readable("Ah")=="ТУЗ ♥ ЧЕРВИ","Ace hearts")
        ok(VisibleCardOverlayText.readable("6s")=="6 ♠ ПИКИ","Six spades")
        ok(VisibleCardOverlayText.readable("Td")=="10 ♦ БУБНЫ","Ten diamonds")
        ok(VisibleCardOverlayText.readable("Qc")=="ДАМА ♣ КРЕСТИ","Queen clubs")
        ok(VisibleCardOverlayText.readable("Ks")=="КОРОЛЬ ♠ ПИКИ","King")
        ok(VisibleCardOverlayText.readable("Js")=="ВАЛЕТ ♠ ПИКИ","Jack")
        ok(VisibleCardOverlayText.readable("ZZ")=="НЕ РАСПОЗНАНО","Bad card no guess")
        ok(VisibleCardOverlayText.readable(null)=="НЕ РАСПОЗНАНО","Missing card no guess")
        val partial=CardReading(listOf("Ah",null),listOf("6s","Td",null),false,0.0,"partial")
        val p=requireNotNull(VisibleCardOverlayText.project(partial,null,4,9,1000,1300,true,false,4))
        ok(!p.isConfirmed && p.status.contains("ПРЕДВАРИТЕЛЬНО"),"Single frame provisional")
        ok("ТУЗ ♥ ЧЕРВИ" in p.hero && "НЕ РАСПОЗНАНО" in p.hero,"Incomplete hand visible")
        ok("6 ♠ ПИКИ" in p.board && "10 ♦ БУБНЫ" in p.board,"Partial board visible")
        val full=CardReading(listOf("Ah","Kd"),listOf("6s","Td","2c"),true,.93,"template")
        val stable=StableCardReading(1000,4,9,listOf("Ah","Kd"),listOf("6s","Td","2c"),.93)
        val confirmed=requireNotNull(VisibleCardOverlayText.project(
            full,stable,4,9,1000,1300,true,false,4))
        ok(confirmed.isConfirmed && confirmed.status.contains("2 КАДРАХ"),"Two-frame agreement")
        ok(!requireNotNull(VisibleCardOverlayText.project(
            full,stable.copy(frameSequence=8),4,9,1000,1300,true,false,4)).isConfirmed,
            "Old consensus not accepted")
        ok(VisibleCardOverlayText.project(full,stable,4,9,1000,1300,true,true,4)==null,"STOP")
        ok(VisibleCardOverlayText.project(full,stable,4,9,1000,1300,false,false,4)==null,"No capture")
        ok(VisibleCardOverlayText.project(full,stable,4,9,1000,1300,true,false,5)==null,"Epoch")
        ok(VisibleCardOverlayText.project(full,stable,4,9,1000,4200,true,false,4)==null,"Stale")
        val empty=CardReading(emptyList(),emptyList(),false,0.0,"No cards")
        val q=requireNotNull(VisibleCardOverlayText.project(empty,null,4,11,1000,1250,true,false,4))
        ok(q.hero=="ИЩУ ДВЕ КАРТЫ" && q.board=="ОБЩИЕ КАРТЫ НЕ НАЙДЕНЫ","Missing text")
        println("VisibleCardOverlayScenarios: "+n+" checks PASS")
    }
}
