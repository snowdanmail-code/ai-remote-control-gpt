package com.example.airemote

object VerifiedTurnBindingScenarios {
    @JvmStatic fun main(args: Array<String>) {
        var checks = 0
        fun checkTrue(b: Boolean) { checks++; check(b) }
        val proof = ExternalTurnAttestation("independent-provider", "a", "table7", "hand7", "turn9",
            "com.vendor.test", 2, 5, 10, 1000, 2000)
        val view = VerifiedLiveView(2,10,1000,"hand7","s",true,true,true,true,setOf(LiveAction.CHECK))
        val visual = TurnCycleReport(TurnCyclePhase.VISIBLE_DIAGNOSTIC,1,10,"read only")
        val gate = VerifiedTurnBinding(ExternalAttestationVerifier { it.providerId == "independent-provider" })
        fun inspect(p: ExternalTurnAttestation? = proof, v: VerifiedLiveView? = view,
            pkg: String? = "com.vendor.test", win: Long? = 5L, now: Long = 1100L,
            stop: Boolean = false, turn: TurnCycleReport? = visual.copy(latestFrame=v?.frameSequence ?: -1L)): TurnBindingResult =
            gate.inspect(p,v,pkg,win,turn,now,stop)
        checkTrue(inspect().status == TurnBindingStatus.WAITING)
        val next = proof.copy(proofId="b",frameSequence=11,capturedAtMs=1200,expiresAtMs=2200)
        val nextView = view.copy(frameSequence=11,capturedAtMs=1200)
        val good = inspect(next,nextView,now=1250)
        checkTrue(good.status == TurnBindingStatus.CONSISTENT_READ_ONLY)
        checkTrue(!good.authorizesTap)
        checkTrue(inspect(next,nextView,now=1260).status == TurnBindingStatus.REJECTED)
        checkTrue(inspect().status == TurnBindingStatus.WAITING)
        checkTrue(inspect(next.copy(handId="other"),nextView,now=1250).status == TurnBindingStatus.REJECTED)
        checkTrue(inspect().status == TurnBindingStatus.WAITING)
        checkTrue(inspect(next,nextView,stop=true).status == TurnBindingStatus.REJECTED)
        checkTrue(inspect().status == TurnBindingStatus.WAITING)
        checkTrue(inspect(next,nextView,pkg="com.other",now=1250).status == TurnBindingStatus.REJECTED)
        checkTrue(inspect().status == TurnBindingStatus.WAITING)
        checkTrue(inspect(next,nextView,win=6,now=1250).status == TurnBindingStatus.REJECTED)
        checkTrue(inspect().status == TurnBindingStatus.WAITING)
        checkTrue(inspect(next,nextView,turn=TurnCycleReport(TurnCyclePhase.CLOSED,1,11,"no"),now=1250).status == TurnBindingStatus.REJECTED)
        checkTrue(inspect().status == TurnBindingStatus.WAITING)
        checkTrue(inspect(next,nextView,now=5000).status == TurnBindingStatus.REJECTED)
        // After a truly dispatched action, a provider-signed NEW frame may
        // confirm its effect even though the action strip is now closed.
        val settledGate = VerifiedTurnBinding(
            ExternalAttestationVerifier { it.providerId == "independent-provider" })
        checkTrue(settledGate.inspect(proof,view,"com.vendor.test",5,visual,1100,false)
            .status == TurnBindingStatus.WAITING)
        checkTrue(settledGate.inspect(next,nextView,"com.vendor.test",5,
            visual.copy(latestFrame=11),1250,false)
            .status == TurnBindingStatus.CONSISTENT_READ_ONLY)
        val settledProof=next.copy(proofId="c",frameSequence=12,
            capturedAtMs=1400,expiresAtMs=2500)
        val closedView=nextView.copy(frameSequence=12,capturedAtMs=1400,
            turnVerified=false,exactLabelsVerified=false,legalActions=emptySet())
        val closed=TurnCycleReport(TurnCyclePhase.CLOSED,1,12,"game advanced")
        checkTrue(settledGate.inspect(settledProof,closedView,"com.vendor.test",5,
            closed,1450,false,true).status == TurnBindingStatus.CONSISTENT_READ_ONLY)
        checkTrue(settledGate.inspect(settledProof,closedView,"com.vendor.test",5,
            closed,1451,false,false).status == TurnBindingStatus.REJECTED)
        val badGate = VerifiedTurnBinding(ExternalAttestationVerifier { false })
        checkTrue(badGate.inspect(proof,view,"com.vendor.test",5,visual,1100,false).status == TurnBindingStatus.REJECTED)
        val viewport=CaptureViewport(1080,2340,1080,2340,0,0,1080,2340)
        val cue=ActionStripCue(HeroControlLane.CHECK_CALL,ExactActionCandidate.CHECK,
            NormalizedTextBox(420,910,620,930),"CHECK")
        val rectangle=VerifiedScreenProjection.project(cue,viewport,"com.vendor.test",5)
        checkTrue(rectangle!=null && rectangle.exactAction==LiveAction.CHECK && rectangle.centerX in 450..700)
        checkTrue(VerifiedScreenProjection.project(cue,viewport.copy(rotated=true),"com.vendor.test",5)==null)
        checkTrue(VerifiedScreenProjection.project(cue,viewport.copy(contentHeight=2300),"com.vendor.test",5)==null)
        checkTrue(VerifiedScreenProjection.project(cue.copy(observedText="RAISE"),viewport,"com.vendor.test",5)==null)
        checkTrue(VerifiedScreenProjection.project(cue.copy(action=ExactActionCandidate.CONFIRM),viewport,"com.vendor.test",5)==null)
        checkTrue(VerifiedScreenProjection.project(cue,viewport,"com.vendor.test",-1)==null)
        println("VerifiedTurnBindingScenarios: $checks checks passed")
    }
}
