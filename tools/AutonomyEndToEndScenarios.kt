package com.example.airemote

/** End-to-end CONTRACT replay with SYNTHETIC, explicitly trusted test providers.
 * It does not claim to extract a verified game state from the installed 1win app.
 */
object AutonomyEndToEndScenarios {
    @JvmStatic fun main(args: Array<String>) {
        var count=0
        fun ok(condition:Boolean){check(condition);count++}
        val pkg="com.vendor.test"
        val base=VerifiedLiveView(9,100,1000,"hand-x","before",true,true,true,true,setOf(LiveAction.CHECK))
        val visual=TurnCycleReport(TurnCyclePhase.VISIBLE_DIAGNOSTIC,1,100,"stable visual")
        val identity=VerifiedTurnBinding(ExternalAttestationVerifier { it.providerId=="trusted-table-provider" })
        fun att(id:String,seq:Long,at:Long)=ExternalTurnAttestation("trusted-table-provider",id,
            "table-x","hand-x","turn-3",pkg,9,4,seq,at,at+800)
        ok(identity.inspect(att("first",100,1000),base,pkg,4,visual,1100,false).status==TurnBindingStatus.WAITING)
        val current=base.copy(frameSequence=101,capturedAtMs=1150)
        val corroborated=identity.inspect(att("second",101,1150),current,pkg,4,visual.copy(latestFrame=101),1180,false)
        ok(corroborated.status==TurnBindingStatus.CONSISTENT_READ_ONLY && !corroborated.authorizesTap)
        val admission=VerifiedDispatchAdmission()
        val target=VerifiedTapArea(370,2050,690,2150,530,2100,1080,2340,
            LiveAction.CHECK,"CHECK",pkg,4)
        ok(admission.admit(current,pkg,4,1180,corroborated.status==TurnBindingStatus.CONSISTENT_READ_ONLY,false))
        ok(admission.claim(current,target,LiveAction.CHECK,1190,false))
        ok(!admission.claim(current,target,LiveAction.CHECK,1191,false))
        val effects=AuthenticatedActionEffects(IndependentEffectVerifier { it.providerId=="trusted-result-provider" })
        val guard=LiveGameActionGuard(effectTicketVerifier={ticket,cmd,v,ms -> effects.accept(ticket,cmd,v,ms)})
        ok(guard.armByOperator())
        ok(guard.begin("command-x",LiveAction.CHECK,current,1200,true)==LiveCommandStatus.READY_TO_DISPATCH)
        ok(guard.claimDispatch("command-x"))
        val original=LiveCommand("command-x","hand-x",LiveAction.CHECK,9,101,"before",1200,1150)
        val animation=current.copy(frameSequence=102,capturedAtMs=1350,stateFingerprint="animation",actionEffectVerified=true)
        ok(guard.observe(animation,1400)==LiveCommandStatus.AWAITING_CONFIRMATION)
        fun proof(id:String,seq:Long,at:Long)=PostActionReceipt("trusted-result-provider",id,
            "command-x","hand-x",LiveAction.CHECK,9,4,seq,at,"after",true,false,10,10,100,100)
        ok(effects.inspect(original,proof("effect-one",103,1450),1490,false).status==EffectGateStatus.COLLECTING)
        val result=effects.inspect(original,proof("effect-two",104,1550),1580,false)
        ok(result.status==EffectGateStatus.CONFIRMED_EFFECT)
        val after=current.copy(frameSequence=104,capturedAtMs=1550,stateFingerprint="after",
            actionEffectVerified=false,verifiedEffectTicket=result.ticket,turnVerified=false)
        ok(guard.observe(after,1580)==LiveCommandStatus.CONFIRMED)
        ok(!effects.accept(result.ticket!!,original,after,1590))
        ok(!guard.claimDispatch("command-x"))
        // STOP revokes everything and never affects post-hand persistent memory.
        ok(admission.admit(current,pkg,4,1180,true,false))
        admission.clear();identity.reset();effects.reset();guard.stop()
        ok(!admission.claim(current,target,LiveAction.CHECK,1190,false))
        ok(guard.begin("command-y",LiveAction.CHECK,current,1200,true)==LiveCommandStatus.REFUSED)
        ok(identity.inspect(att("fake",102,1200).copy(providerId="impostor"),
            current.copy(frameSequence=102,capturedAtMs=1200),pkg,4,visual,1250,false).status==TurnBindingStatus.REJECTED)
        // Unauthenticated visual text is never enough for a tap.
        ok(!admission.admit(current,pkg,4,1190,false,false))
        println("AutonomyEndToEndScenarios: $count checks passed")
    }
}
