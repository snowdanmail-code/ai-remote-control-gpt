package com.example.airemote

import org.junit.Assert.assertEquals
import org.junit.Test

/** Validates just-in-time gate; no game simulator and no system taps. */
class LiveDispatchFreshnessJvmTest {
    private val c = LiveCommand("req-1", "hand-1", LiveAction.CHECK,
        7L, 18L, "s1", 10_000L, 9_500L)
    private fun check(at: Long = 10_250L, epoch: Long = 7L, active: Boolean = true,
                      root: String? = "org.example.poker", cmd: LiveCommand = c) =
        LiveDispatchFreshness.verify(cmd, at, epoch, active, root, "org.example.poker")

    @Test fun acceptedOnlyWithinQueueAndFrameWindows() {
        assertEquals(LiveDispatchCheck.VALID, check())
    }
    @Test fun queuedTooLongMustNotDispatch() {
        assertEquals(LiveDispatchCheck.LATE_QUEUE, check(at=10_650L))
    }
    @Test fun staleGameFrameMustNotDispatch() {
        assertEquals(LiveDispatchCheck.STALE_OBSERVATION,
            check(cmd=c.copy(sourceCapturedAtMs=8_000L)))
    }
    @Test fun newMediaProjectionMustNotDispatchOldCommand() {
        assertEquals(LiveDispatchCheck.DIFFERENT_CAPTURE, check(epoch=8L))
    }
    @Test fun captureStoppedMustNotDispatch() {
        assertEquals(LiveDispatchCheck.MISSING_CAPTURE, check(active=false))
    }
    @Test fun systemDialogMustNotReceiveGameGesture() {
        assertEquals(LiveDispatchCheck.UNVERIFIED_FOREGROUND,
            check(root="com.android.systemui"))
    }
    @Test fun missingAccessibleRootMustNotUseLastSeenWindow() {
        assertEquals(LiveDispatchCheck.UNVERIFIED_FOREGROUND, check(root=null))
    }
}
