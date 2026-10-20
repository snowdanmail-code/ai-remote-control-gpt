package com.example.airemote

import org.junit.Assert.*
import org.junit.Test

/** Code contract for the real Android gesture transport. Does not simulate poker. */
class LiveTapSafetyJvmTest {
    private val command = LiveCommand("unique", "hand", LiveAction.CALL, 2, 12, "fingerprint", 500)
    private val target = VerifiedTapArea(245, 2000, 480, 2100, 350, 2040,
        1080, 2340, LiveAction.CALL, "CALL", "com.example.authorizedgame")
    private fun result(t: VerifiedTapArea = target, foreground: String? = "com.example.authorizedgame",
                       approved: String? = "com.example.authorizedgame", now: Long = 1700,
                       observed: Long = 1400): TapSafetyResult =
        LiveTapSafety.verify(t, command, foreground, observed, now, 1080, 2340, approved)

    @Test fun exactActionInTrustedTargetAccepted() {
        assertEquals(TapSafetyResult.VALID, result())
    }
    @Test fun incorrectForegroundRejected() {
        assertEquals(TapSafetyResult.WRONG_PACKAGE, result(foreground="com.android.settings"))
    }
    @Test fun noApprovedPackageRejected() {
        assertEquals(TapSafetyResult.WRONG_PACKAGE, result(approved=null))
    }
    @Test fun staleWindowEvidenceRejected() {
        assertEquals(TapSafetyResult.STALE_FOREGROUND, result(now=3000))
    }
    @Test fun otherActionIsNotEquivalent() {
        assertEquals(TapSafetyResult.WRONG_ACTION, result(t=target.copy(exactAction=LiveAction.CHECK)))
    }
    @Test fun onlyExactLabelAllowed() {
        assertEquals(TapSafetyResult.UNVERIFIED_LABEL, result(t=target.copy(recognizedLabel="CALL 100")))
    }
    @Test fun outOfBoundsButtonRejected() {
        assertEquals(TapSafetyResult.INVALID_GEOMETRY, result(t=target.copy(right=1400)))
    }
    @Test fun systemBarExcluded() {
        assertEquals(TapSafetyResult.SYSTEM_UI_RISK, result(t=target.copy(top=75,bottom=160,centerY=110)))
    }
    @Test fun androidNavigationExcluded() {
        assertEquals(TapSafetyResult.SYSTEM_UI_RISK, result(t=target.copy(top=2190,bottom=2320,centerY=2200)))
    }
    @Test fun initiallyUnapprovedAndStopRevokes() {
        LiveGamePermissionGate.revoke()
        assertFalse(LiveGamePermissionGate.permitted())
        LiveGamePermissionGate.grantForVerifiedEnvironment("com.example.authorizedgame", false)
        assertFalse(LiveGamePermissionGate.permitted())
    }
}
