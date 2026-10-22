package com.example.airemote

object LocalPlayerCheckScenarios {
    @JvmStatic fun main(args: Array<String>) {
        var n=0
        fun ok(v:Boolean, label:String) { check(v) { label }; n++ }
        val pkg = "example.virtual.poker"
        fun board(at:Long, seq:Long=8): StableCardReading =
            StableCardReading(at, 7, seq, listOf("Ah","Td"), listOf("3c","9s","Jh"), .96)
        val nativeButton=NativeActionButton(LiveAction.CHECK,"CHECK",350,1910,660,2040,
            pkg,5,1100)
        fun snap(now:Long, actions:List<NativeActionButton> =
            listOf(nativeButton.copy(observedAtMs=now))) =
            NativeControlSnapshot(pkg,5,now,actions,12,false)
        val gate=LocalVisualCheckGate()
        ok(gate.observe(board(1000),snap(1100),pkg,5,7,1100,1080,2340)==null,
            "disarmed does not act")
        ok(gate.arm(),"explicit arm")
        ok(gate.observe(board(1000),snap(1100),pkg,5,7,1100,1080,2340)==null,
            "one native scan is not enough")
        val ticket=gate.observe(board(1000),snap(1450),pkg,5,7,1450,1080,2340)
        ok(ticket!=null && ticket.button.action==LiveAction.CHECK,"two-sample free check")
        ok(gate.claim(ticket!!,1460),"consume once")
        ok(!gate.claim(ticket,1461),"no replay")
        ok(gate.observe(board(1000),snap(1550),pkg,5,7,1550,1080,2340)==null,
            "no repeated action while button displayed")
        ok(gate.observe(board(1700,9),snap(1800,emptyList()),pkg,5,7,1800,1080,2340)==null,
            "first missing not completion proof")
        ok(gate.observe(board(1700,9),snap(2070,emptyList()),pkg,5,7,2070,1080,2340)==null,
            "second missing clears only retry latch")
        ok(gate.observe(board(2000,10),snap(2150),pkg,5,7,2150,1080,2340)==null,
            "next turn needs two native scans")
        ok(gate.observe(board(2000,10),snap(2430),pkg,5,7,2430,1080,2340)!=null,
            "another visible CHECK allowed after disappearance")
        gate.stop("STOP")
        ok(gate.status().state==LocalCheckState.OFF,"hard stop")
        ok(gate.observe(board(2500),snap(2510),pkg,5,7,2510,1080,2340)==null,
            "no gesture after stop")
        val mismatch=LocalVisualCheckGate()
        mismatch.arm()
        ok(mismatch.observe(board(1000),snap(1100),pkg,6,7,1100,1080,2340)==null,
            "window generation mismatch")
        val paid=NativeActionButton(LiveAction.CALL,"CALL",350,1910,660,2040,pkg,5,1100)
        ok(mismatch.observe(board(1000),snap(1110,listOf(paid)),pkg,5,7,1110,1080,2340)==null,
            "CALL never automatically clicked")
        ok(mismatch.observe(board(1000),snap(1120,listOf(nativeButton.copy(observedAtMs=1120),
            paid.copy(observedAtMs=1120))),pkg,5,7,1120,1080,2340)==null,
            "ambiguous CHECK/CALL blocked")
        ok(mismatch.observe(board(1000),snap(1130),pkg,5,8,1130,1080,2340)==null,
            "epoch mismatch")
        println("LocalPlayerCheckScenarios: $n/$n passed")
    }
}
