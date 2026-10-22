package com.example.airemote

/**
 * Complete OFFLINE contract replay:
 * captured cards + independent source -> strategy -> one CHECK dispatch ->
 * two authenticated causal receipts -> next signed turn.
 *
 * The provider is deliberately a named synthetic fixture; this test NEVER
 * claims any live 1win source is connected or that an Android device was tapped.
 */
object AuthorizedPlayerFullCycleScenarios {
    @JvmStatic fun main(args: Array<String>) {
        var count = 0
        fun ok(value: Boolean, label: String) {
            check(value) { label }
            count++
        }
        val pkg = "com.example.virtualpoker"
        val source = ExternalAttestationVerifier {
            it.providerId == "synthetic-authorized-provider" && it.proofId.startsWith("signed-")
        }
        val joiner = AuthenticatedPlayerFrameJoiner(source)
        val resultGate = AuthorizedPostActionFrameGate(source)
        val effects = AuthenticatedActionEffects(IndependentEffectVerifier {
            it.providerId == "synthetic-authorized-provider" && it.proofId.startsWith("signed-")
        })
        val hand = "hand-001"
        val table = "table-abc"
        fun proof(sequence: Long, time: Long, turn: String = "turn-1") =
            ExternalTurnAttestation("synthetic-authorized-provider", "signed-$sequence",
                table, hand, turn, pkg, 7L, 5L, sequence, time, time + 1000)
        fun jpeg(sequence: Long, time: Long) =
            FrameInfo(byteArrayOf(1,2,3), sequence, 443, 960, 75, 0,
                time, 7L, pkg, 5L)
        val viewport = CaptureViewport(443,960,1080,2340,0,0,1080,2340)
        val hole = listOf("Ah","Kd")
        val board = listOf("3c","9s","Jh")
        fun cards(t: Long, boardCards: List<String> = board) =
            StableCardReading(t - 70,7,10,hole,boardCards,.96)
        fun controls(seq: Long) = ActionStripReport(ActionStripStatus.STABLE_DIAGNOSTIC,
            "two different measured JPEGs", seq, stable=listOf(
                ActionStripCue(HeroControlLane.FOLD,ExactActionCandidate.FOLD,
                    NormalizedTextBox(35,933,300,969),"Пас"),
                ActionStripCue(HeroControlLane.CHECK_CALL,ExactActionCandidate.CHECK,
                    NormalizedTextBox(348,933,648,969),"Чек")
            ))
        fun packet(seq: Long, at: Long, turn: String = "turn-1",
                   stage: HandStage = HandStage.FLOP, boardCards: List<String> = board) =
            IndependentlyAuthenticatedPokerFrame(proof(seq,at,turn),stage,true,
                setOf(LiveAction.FOLD,LiveAction.CHECK),
                120L,0L,4000L,hole,boardCards,"before-$seq")
        val first = joiner.combine(jpeg(12,1000),cards(1000),controls(12),
            packet(12,1000),viewport,1040,false)
        val second = joiner.combine(jpeg(13,1200),cards(1200),controls(13),
            packet(13,1200),viewport,1230,false)
        ok(first.accepted && second.accepted,"join cards, layout and trusted state")
        val binder = VerifiedTurnBinding(source)
        val visual1 = TurnCycleReport(TurnCyclePhase.VISIBLE_DIAGNOSTIC,1,12,"fixture")
        val visual2 = visual1.copy(latestFrame=13)
        ok(binder.inspect(proof(12,1000),first.observation!!.view,pkg,5,
            visual1,1040,false).status==TurnBindingStatus.WAITING,
            "first distinct signed proof is not enough")
        ok(binder.inspect(proof(13,1200),second.observation!!.view,pkg,5,
            visual2,1230,false).status==TurnBindingStatus.CONSISTENT_READ_ONLY,
            "second signed proof binds same hand, turn and window")
        val strategy = VerifiedLocalPokerStrategy()
        ok(strategy.decide(second.observation!!)?.action==LiveAction.CHECK,
            "local strategy makes an allowed free move")
        val guard = LiveGameActionGuard(effectTicketVerifier={ ticket,cmd,view,now ->
            effects.accept(ticket,cmd,view,now)
        })
        ok(guard.armByOperator(),"separate operator arm")
        var submissions = 0
        val transport = object: AutonomousTransport {
            override fun submit(id:String, action:LiveAction, view:VerifiedLiveView,
                                tap:VerifiedTapArea, nowMs:Long):LiveCommandStatus {
                submissions++
                val result=guard.begin(id,action,view,nowMs,true)
                if (result != LiveCommandStatus.READY_TO_DISPATCH) return result
                return if (guard.claimDispatch(id)) LiveCommandStatus.AWAITING_CONFIRMATION
                    else LiveCommandStatus.REFUSED
            }
            override fun observe(view:VerifiedLiveView,nowMs:Long) = guard.observe(view,nowMs)
            override fun stop() { guard.stop() }
        }
        val loop = AutonomousActionLoop(strategy,transport)
        ok(loop.arm(true,true),"loop armed")
        val dispatched=loop.onVerifiedObservation(second.observation,1230,false,true,true)
        ok(dispatched.state==AutoLoopState.WAITING_FOR_RESULT && submissions==1,
            "one legal move sent to transport")
        val cmd=requireNotNull(guard.pendingCommandForEffect())
        val stale = second.observation.copy(
            view=second.observation.view.copy(frameSequence=14,capturedAtMs=1340,
                stateFingerprint="animation-only"))
        val waiting=loop.onVerifiedObservation(stale,1360,false,true,true)
        ok(waiting.state==AutoLoopState.WAITING_FOR_RESULT && submissions==1,
            "game animation alone cannot prove result or cause retry")
        fun post(seq:Long,at:Long) =
            packet(seq,at).copy(ownTurn=false,legalActions=emptySet(),
                stateFingerprint="verified-closed-turn")
        fun receipt(seq:Long,at:Long) = PostActionReceipt(
            "synthetic-authorized-provider", "signed-$seq",cmd.id,hand,LiveAction.CHECK,
            7L,5L,seq,at,"verified-closed-turn",true,false,0,0,120,120)
        val p14 = resultGate.prepare(jpeg(14,1450),post(14,1450),receipt(14,1450),
            cmd,table,"turn-1",1500,false)
        val p15 = resultGate.prepare(jpeg(15,1650),post(15,1650),receipt(15,1650),
            cmd,table,"turn-1",1700,false)
        ok(p14.accepted && p15.accepted,"two post-action proof frames match active command")
        val firstReceipt=effects.inspect(cmd,receipt(14,1450),1500,false)
        ok(firstReceipt.status==EffectGateStatus.COLLECTING,
            "first receipt does not settle the command")
        val secondReceipt=effects.inspect(cmd,receipt(15,1650),1700,false)
        ok(secondReceipt.status==EffectGateStatus.CONFIRMED_EFFECT &&
            secondReceipt.ticket!=null,"two independent post-state receipts corroborate")
        val confirmedObs = p15.frame!!.observation.copy(
            view=p15.frame.observation.view.copy(verifiedEffectTicket=secondReceipt.ticket))
        val confirmed=loop.onVerifiedObservation(confirmedObs,1700,false,true,true)
        ok(confirmed.state==AutoLoopState.CONFIRMED && submissions==1,
            "verified game result, never gesture callback, clears outstanding command")
        ok(guard.pendingCommandForEffect()==null,"effect token consumed once")
        ok(loop.onVerifiedObservation(second.observation,1750,false,true,true)
            .state!=AutoLoopState.WAITING_FOR_RESULT && submissions==1,
            "previous turn is never sent twice")
        // After the first action is authentically confirmed, the same
        // autonomous session should process a DIFFERENT fresh turn. Previously
        // consumed turn-1 must never dispatch twice, even across callbacks.
        val nextTurn = second.observation.copy(
            turnId="turn-2",
            view=second.observation.view.copy(frameSequence=17,
                capturedAtMs=1900, stateFingerprint="turn-2-ready"))
        val nextState = loop.onVerifiedObservation(nextTurn,1930,false,true,true)
        ok(nextState.state==AutoLoopState.WAITING_FOR_RESULT && submissions==2,
            "next independent turn can issue second distinct action")
        val nextCommand = requireNotNull(guard.pendingCommandForEffect())
        ok(nextCommand.id != cmd.id,"next turn generates a distinct command id")
        ok(!resultGate.prepare(jpeg(16,1800),post(16,1800).copy(
            attestation=proof(16,1800,"wrong-turn")),receipt(16,1800),
            cmd,table,"turn-1",1840,false).accepted,
            "wrong turn cannot confirm a previous command")
        ok(!resultGate.prepare(jpeg(16,1800),post(16,1800),receipt(16,1800),
            cmd,table,"turn-1",1840,true).accepted,"STOP refuses result processing")
        loop.stop("STOP")
        ok(loop.onVerifiedObservation(second.observation,1800,true,false,false)
            .state==AutoLoopState.STOPPED,"STOP prevents all subsequent moves")
        println("AuthorizedPlayerFullCycleScenarios: $count checks passed (synthetic provider only)")
    }
}
