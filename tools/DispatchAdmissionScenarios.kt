package com.example.airemote

object DispatchAdmissionScenarios {
    @JvmStatic fun main(args: Array<String>) {
        var n=0
        fun ok(b:Boolean) {check(b);n++}
        val gate=VerifiedDispatchAdmission()
        val view=VerifiedLiveView(5,77,1000,"h1","state-a",true,true,true,true,
            setOf(LiveAction.CHECK,LiveAction.FOLD))
        val tap=VerifiedTapArea(350,2030,700,2150,525,2090,1080,2340,
            LiveAction.CHECK,"CHECK","com.vendor.test",3)
        ok(!gate.claim(view,tap,LiveAction.CHECK,1100,false))
        ok(!gate.admit(view,"com.vendor.test",3,1100,false,false))
        ok(gate.admit(view,"com.vendor.test",3,1100,true,false))
        ok(!gate.claim(view,tap.copy(windowGeneration=4),LiveAction.CHECK,1100,false))
        ok(!gate.claim(view,tap,LiveAction.CHECK,1100,false))
        ok(gate.admit(view,"com.vendor.test",3,1100,true,false))
        ok(!gate.claim(view,tap,LiveAction.CHECK,1100,true))
        ok(gate.admit(view,"com.vendor.test",3,1100,true,false))
        ok(!gate.claim(view,tap,LiveAction.CALL,1100,false))
        ok(gate.admit(view,"com.vendor.test",3,1100,true,false))
        ok(!gate.claim(view.copy(frameSequence=78),tap,LiveAction.CHECK,1100,false))
        ok(gate.admit(view,"com.vendor.test",3,1100,true,false))
        ok(!gate.claim(view,tap,LiveAction.CHECK,2400,false))
        ok(gate.admit(view,"com.vendor.test",3,1100,true,false))
        ok(gate.claim(view,tap,LiveAction.CHECK,1101,false))
        ok(!gate.claim(view,tap,LiveAction.CHECK,1102,false))
        ok(gate.admit(view,"com.vendor.test",3,1100,true,false))
        gate.clear();ok(!gate.claim(view,tap,LiveAction.CHECK,1110,false))
        ok(!gate.admit(view.copy(exactLabelsVerified=false),"com.vendor.test",3,1100,true,false))
        ok(!gate.admit(view,"com.vendor.test",-1,1100,true,false))
        ok(!gate.admit(view,"com.vendor.test",3,3000,true,false))
        println("DispatchAdmissionScenarios: $n checks passed")
    }
}
