package com.example.airemote

import org.junit.Assert.*
import org.junit.Test

/** JVM portion of Android Gradle tests. Device integration is not asserted. */
class HeroTurnLifecycleJvmTest {
    private fun frame(seq: Long, state: TurnDisplayState): TurnVisualSignal {
        val roles = when (state) {
            TurnDisplayState.READY -> setOf(VisibleButtonRole.RED_FOLD,
                VisibleButtonRole.GREEN_CHECK_OR_CALL)
            TurnDisplayState.BET_SIZING -> setOf(VisibleButtonRole.GOLD_CONFIRM_SIZE)
            else -> emptySet()
        }
        return TurnVisualSignal(seq * 500L, 1L, seq, false,
            TurnVisualReading(state, roles, "test", 0.0, 0.0, 0.0))
    }
    private fun observe(lifecycle: HeroTurnLifecycleDiagnostics, seq: Long,
                        state: TurnDisplayState): HeroTurnLifecycleReport =
        lifecycle.inspect(frame(seq,state), "com.example.testgame", 7L,
            true, seq * 500L + 100L, false)

    @Test fun startRequiresWaitingAndTwoActiveFrames() {
        val lifecycle = HeroTurnLifecycleDiagnostics()
        assertEquals(HeroTurnPhase.COLLECTING, observe(lifecycle,1,TurnDisplayState.WAITING).phase)
        assertEquals(HeroTurnPhase.WAITING_VISUAL, observe(lifecycle,2,TurnDisplayState.WAITING).phase)
        assertEquals(HeroTurnPhase.COLLECTING, observe(lifecycle,3,TurnDisplayState.READY).phase)
        val result = observe(lifecycle,4,TurnDisplayState.READY)
        assertEquals(HeroTurnPhase.TURN_APPEARED_VISUAL, result.phase)
        assertFalse(result.authorizesTap)
        assertFalse(result.confirmsGameTurn)
    }

    @Test fun stopErasesTurnContext() {
        val lifecycle = HeroTurnLifecycleDiagnostics()
        observe(lifecycle,1,TurnDisplayState.WAITING)
        observe(lifecycle,2,TurnDisplayState.WAITING)
        observe(lifecycle,3,TurnDisplayState.READY)
        assertEquals(HeroTurnPhase.TURN_APPEARED_VISUAL,observe(lifecycle,4,TurnDisplayState.READY).phase)
        assertEquals(HeroTurnPhase.REJECTED, lifecycle.inspect(
            frame(5,TurnDisplayState.READY),"com.example.testgame",7L,true,2600L,true).phase)
        assertEquals(HeroTurnPhase.COLLECTING, observe(lifecycle,6,TurnDisplayState.READY).phase)
        assertEquals(HeroTurnPhase.NO_BASELINE,observe(lifecycle,7,TurnDisplayState.READY).phase)
    }
}
