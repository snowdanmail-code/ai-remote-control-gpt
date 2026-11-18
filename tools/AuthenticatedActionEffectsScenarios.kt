package com.example.airemote

object AuthenticatedActionEffectsScenarios {
    @JvmStatic fun main(args: Array<String>) {
        var n=0
        fun ok(c:Boolean) {check(c);n++}
        val before=VerifiedLiveView(1,10,1000,"hand","baseline",true,true,true,true,setOf(LiveAction.CHECK))
        val cmd=LiveCommand("cmd-1","hand",LiveAction.CHECK,1,10,"baseline",1010,1000)
        ok(!OnDeviceEffectAuthority.configured())
        OnDeviceEffectAuthority.install(IndependentEffectVerifier { it.providerId=="trusted-test" })
        ok(OnDeviceEffectAuthority.configured())
        OnDeviceEffectAuthority.clear()
        ok(!OnDeviceEffectAuthority.configured())
        val provider=AuthenticatedActionEffects(IndependentEffectVerifier { it.providerId=="trusted-test" })
        fun receipt(seq:Long=11, id:String="p1", at:Long=1200) = PostActionReceipt(
            "trusted-test",id,"cmd-1","hand",LiveAction.CHECK,1,2,seq,at,"settled-state",
            true,false,10,10,200,200)
        ok(provider.inspect(cmd,receipt(),1250,false).status==EffectGateStatus.COLLECTING)
        val verified=provider.inspect(cmd,receipt(12,"p2",1400),1450,false)
        ok(verified.status==EffectGateStatus.CONFIRMED_EFFECT)
        val token=verified.ticket!!
        val after=before.copy(frameSequence=12,capturedAtMs=1400,stateFingerprint="settled-state",
            actionEffectVerified=true,verifiedEffectTicket=token)
        val guard=LiveGameActionGuard(effectTicketVerifier={ticket, c, v, ms -> provider.accept(ticket,c,v,ms)})
        ok(guard.armByOperator())
        ok(guard.begin(cmd.id,cmd.action,before,1010,true)==LiveCommandStatus.READY_TO_DISPATCH)
        ok(guard.claimDispatch(cmd.id))
        ok(guard.observe(before.copy(frameSequence=11,capturedAtMs=1200,stateFingerprint="animation",
            actionEffectVerified=true),1250)==LiveCommandStatus.AWAITING_CONFIRMATION)
        ok(guard.observe(after,1450)==LiveCommandStatus.CONFIRMED)
        ok(!provider.accept(token,cmd,after,1455))
        val defaultGuard=LiveGameActionGuard()
        ok(defaultGuard.armByOperator())
        ok(defaultGuard.begin("another",LiveAction.CHECK,before,1010,true)==LiveCommandStatus.READY_TO_DISPATCH)
        ok(defaultGuard.claimDispatch("another"))
        ok(defaultGuard.observe(after,1450)==LiveCommandStatus.AWAITING_CONFIRMATION)
        ok(provider.inspect(cmd,receipt(),1250,true).status==EffectGateStatus.REJECTED)
        ok(provider.inspect(cmd,receipt().copy(contributionAfter=11),1250,false).status==EffectGateStatus.REJECTED)
        ok(provider.inspect(cmd,receipt(),1250,false).status==EffectGateStatus.COLLECTING)
        ok(provider.inspect(cmd,receipt(12,"p1",1400),1450,false).status==EffectGateStatus.REJECTED)
        ok(provider.inspect(cmd,receipt(),1250,false).status==EffectGateStatus.COLLECTING)
        ok(provider.inspect(cmd,receipt(12,"p2",1400).copy(windowGeneration=3),1450,false).status==EffectGateStatus.REJECTED)
        ok(provider.inspect(cmd,receipt(),1250,false).status==EffectGateStatus.COLLECTING)
        ok(provider.inspect(cmd,receipt(12,"p2",1400).copy(potAfter=201),1450,false).status==EffectGateStatus.REJECTED)
        ok(provider.inspect(cmd,receipt(),1250,false).status==EffectGateStatus.COLLECTING)
        ok(provider.inspect(cmd,receipt(12,"p2",1400).copy(providerId="untrusted"),1450,false).status==EffectGateStatus.REJECTED)
        val folded=cmd.copy(id="cmd-fold",action=LiveAction.FOLD)
        val fold=receipt().copy(commandId="cmd-fold",action=LiveAction.FOLD,heroFolded=true)
        ok(provider.inspect(folded,fold,1250,false).status==EffectGateStatus.COLLECTING)
        ok(provider.inspect(folded,fold.copy(proofId="f2",frameSequence=12,capturedAtMs=1400),1450,false).status==EffectGateStatus.CONFIRMED_EFFECT)
        val bet=cmd.copy(id="bet",action=LiveAction.BET)
        val betReceipt=receipt().copy(commandId="bet",action=LiveAction.BET,contributionAfter=20,potAfter=210)
        ok(provider.inspect(bet,betReceipt,1250,false).status==EffectGateStatus.COLLECTING)
        ok(provider.inspect(bet,betReceipt.copy(proofId="b2",frameSequence=12,capturedAtMs=1400),1450,false).status==EffectGateStatus.CONFIRMED_EFFECT)
        println("AuthenticatedActionEffectsScenarios: $n checks passed")
    }
}
