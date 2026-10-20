package com.example.airemote

object HeroTurnLifecycleScenarios {
    private var count = 0
    private fun ck(test: Boolean, detail: String) { count++; check(test) { detail } }
    private fun reading(state: TurnDisplayState): TurnVisualReading {
        val roles = when (state) {
            TurnDisplayState.READY -> setOf(VisibleButtonRole.RED_FOLD,
                VisibleButtonRole.GREEN_CHECK_OR_CALL, VisibleButtonRole.GOLD_BET_OR_RAISE)
            TurnDisplayState.BET_SIZING -> setOf(VisibleButtonRole.GOLD_CONFIRM_SIZE)
            else -> emptySet()
        }
        return TurnVisualReading(state, roles, "test", 0.5, 0.5, 0.5)
    }
    private fun accept(observer: HeroTurnLifecycleDiagnostics, seq: Long,
        state: TurnDisplayState, epoch: Long = 1L, gen: Long = 3L,
        pkg: String = "com.vendor.app", stopped: Boolean = false,
        safe: Boolean = true, capturedAt: Long = seq * 500L,
        now: Long = capturedAt + 100L,
        overrideReading: TurnVisualReading? = null
    ): HeroTurnLifecycleReport = observer.inspect(
        TurnVisualSignal(capturedAt, epoch, seq, false, overrideReading ?: reading(state)),
        pkg, gen, safe, now, stopped)
    @JvmStatic fun main(args: Array<String>) {
        val gate = HeroTurnLifecycleDiagnostics()
        fun phase(seq: Long, state: TurnDisplayState) = accept(gate,seq,state).phase
        ck(phase(1,TurnDisplayState.READY) == HeroTurnPhase.COLLECTING, "first ready cannot start turn")
        ck(phase(2,TurnDisplayState.READY) == HeroTurnPhase.NO_BASELINE, "initial ready unanchored")
        ck(phase(3,TurnDisplayState.READY) == HeroTurnPhase.NO_BASELINE, "initial ready repeat still not proof")
        ck(phase(4,TurnDisplayState.WAITING) == HeroTurnPhase.COLLECTING, "first waiting frame")
        ck(phase(5,TurnDisplayState.WAITING) == HeroTurnPhase.WAITING_VISUAL, "anchored waiting")
        ck(phase(6,TurnDisplayState.READY) == HeroTurnPhase.COLLECTING, "first ready after wait")
        val started = accept(gate,7,TurnDisplayState.READY)
        ck(started.phase == HeroTurnPhase.TURN_APPEARED_VISUAL, "visual start after two new frames")
        ck(started.localVisualEpisode == 1L, "local episode increments once")
        ck(!started.authorizesTap && !started.confirmsGameTurn, "never grants action rights")
        ck(phase(8,TurnDisplayState.READY) == HeroTurnPhase.ACTIVE_VISUAL, "stable active stays active")
        ck(phase(9,TurnDisplayState.BET_SIZING) == HeroTurnPhase.COLLECTING, "sizing once not end")
        ck(phase(10,TurnDisplayState.BET_SIZING) == HeroTurnPhase.SIZING_VISUAL, "sizing confirmed")
        ck(phase(11,TurnDisplayState.READY) == HeroTurnPhase.COLLECTING, "return from sizing first frame")
        ck(phase(12,TurnDisplayState.READY) == HeroTurnPhase.ACTIVE_VISUAL, "return from sizing is not new turn")
        ck(phase(13,TurnDisplayState.WAITING) == HeroTurnPhase.COLLECTING, "one inactive frame not end")
        val ended = accept(gate,14,TurnDisplayState.WAITING)
        ck(ended.phase == HeroTurnPhase.TURN_DISAPPEARED_VISUAL, "2 inactive frames end visual episode")
        ck(ended.localVisualEpisode == 1L, "end retains episode number")
        ck(phase(15,TurnDisplayState.WAITING) == HeroTurnPhase.WAITING_VISUAL, "waiting continued")
        ck(phase(16,TurnDisplayState.READY) == HeroTurnPhase.COLLECTING, "new ready once no new turn")
        val again = accept(gate,17,TurnDisplayState.READY)
        ck(again.phase == HeroTurnPhase.TURN_APPEARED_VISUAL, "new visual episode")
        ck(again.localVisualEpisode == 2L, "second local episode")
        ck(!again.authorizesTap && !again.confirmsGameTurn, "even second turn read-only")
        ck(accept(gate,17,TurnDisplayState.READY).phase == HeroTurnPhase.REJECTED,
            "repeated frame invalidated")
        ck(phase(18,TurnDisplayState.READY) == HeroTurnPhase.COLLECTING,
            "after repeat need baseline again")
        ck(phase(19,TurnDisplayState.READY) == HeroTurnPhase.NO_BASELINE,
            "after repeat initial ready unanchored")
        ck(phase(20,TurnDisplayState.UNKNOWN) == HeroTurnPhase.REJECTED,
            "unknown frame invalidates")
        ck(accept(gate,21,TurnDisplayState.WAITING,stopped=true).phase == HeroTurnPhase.REJECTED,
            "operator STOP immediate invalidation")
        ck(phase(22,TurnDisplayState.WAITING) == HeroTurnPhase.COLLECTING,
            "fresh evidence required after STOP")
        ck(phase(23,TurnDisplayState.PREACTION) == HeroTurnPhase.COLLECTING,
            "different wait styles require separate 2 frame proof")
        ck(phase(24,TurnDisplayState.PREACTION) == HeroTurnPhase.WAITING_VISUAL,
            "preaction becomes anchored waiting")
        ck(phase(25,TurnDisplayState.READY) == HeroTurnPhase.COLLECTING,
            "first ready after preaction")
        ck(phase(26,TurnDisplayState.READY) == HeroTurnPhase.TURN_APPEARED_VISUAL,
            "second ready after preaction")
        ck(accept(gate,27,TurnDisplayState.READY,gen=4L).phase == HeroTurnPhase.REJECTED,
            "window generation changed invalidates")
        ck(accept(gate,28,TurnDisplayState.WAITING,gen=4L).phase == HeroTurnPhase.COLLECTING,
            "window change needs own waiting baseline")
        ck(accept(gate,29,TurnDisplayState.WAITING,gen=4L).phase == HeroTurnPhase.WAITING_VISUAL,
            "reanchored after new window")
        ck(accept(gate,30,TurnDisplayState.READY,gen=4L,epoch=2L).phase == HeroTurnPhase.REJECTED,
            "epoch changed invalidates")
        ck(accept(gate,31,TurnDisplayState.WAITING,gen=4L,epoch=2L).phase == HeroTurnPhase.COLLECTING,
            "epoch change resets baseline")
        ck(accept(gate,32,TurnDisplayState.WAITING,gen=4L,epoch=2L).phase == HeroTurnPhase.WAITING_VISUAL,
            "fresh epoch waiting")
        ck(accept(gate,33,TurnDisplayState.READY,gen=4L,epoch=2L,pkg="com.other").phase == HeroTurnPhase.REJECTED,
            "source package change")
        ck(accept(gate,34,TurnDisplayState.WAITING,epoch=2L,safe=false).phase == HeroTurnPhase.REJECTED,
            "lost foreground probe")
        ck(accept(gate,35,TurnDisplayState.WAITING,capturedAt=17000L,now=20000L).phase == HeroTurnPhase.REJECTED,
            "stale frame")
        ck(accept(gate,36,TurnDisplayState.WAITING,capturedAt=18500L,now=18000L).phase == HeroTurnPhase.REJECTED,
            "future frame")
        ck(accept(gate,37,TurnDisplayState.WAITING,gen=-1L).phase == HeroTurnPhase.REJECTED,
            "unknown window generation")
        val badRoles = reading(TurnDisplayState.READY).copy(roles=setOf(VisibleButtonRole.RED_FOLD))
        ck(accept(gate,38,TurnDisplayState.READY,overrideReading=badRoles).phase == HeroTurnPhase.REJECTED,
            "partial colored buttons cannot indicate ready")
        ck(accept(gate,39,TurnDisplayState.WAITING,overrideReading=reading(TurnDisplayState.WAITING)
            .copy(roles=setOf(VisibleButtonRole.RED_FOLD))).phase == HeroTurnPhase.REJECTED,
            "inconsistent waiting roles")
        ck(accept(gate,40,TurnDisplayState.WAITING).phase == HeroTurnPhase.COLLECTING,
            "reset after conflicting roles")
        ck(accept(gate,50,TurnDisplayState.WAITING).phase == HeroTurnPhase.REJECTED,
            "large frame gap invalidated")
        ck(accept(gate,51,TurnDisplayState.WAITING).phase == HeroTurnPhase.COLLECTING,
            "must restart sampling after large gap")
        gate.reset()
        ck(accept(gate,1,TurnDisplayState.READY).phase == HeroTurnPhase.COLLECTING,
            "manual reset clears observations")
        println("HeroTurnLifecycleScenarios: $count checks passed")
    }
}
