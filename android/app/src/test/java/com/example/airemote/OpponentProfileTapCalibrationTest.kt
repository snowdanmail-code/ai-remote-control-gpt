package com.example.airemote

import org.junit.Assert.*
import org.junit.Test

class OpponentProfileTapCalibrationTest {
    private fun zone(role: RealFieldRole, l: Int, t: Int, r: Int, b: Int,
                     seat: Int? = null) =
        RealFrameZone(role, NormalizedTextBox(l, t, r, b), seat)

    private fun profile(avatar: RealFrameZone =
                            zone(RealFieldRole.SEAT_AVATAR, 150, 270, 235, 355, 0),
                        dismiss: RealFrameZone =
                            zone(RealFieldRole.PROFILE_DISMISS, 245, 470, 285, 520)) =
        RealFrameCalibration("1win-portrait-calibrated",
            "com.example.testgame", 1080, 2340, listOf(
                zone(RealFieldRole.SEAT_ACTION, 120, 240, 270, 365, 0),
                zone(RealFieldRole.SEAT_ACTION, 680, 240, 810, 365, 1),
                avatar, dismiss
            ))

    @Test fun measuredAvatarAndEmptyTableDismissAreValid() {
        val p = profile()
        assertTrue(p.valid())
        assertEquals(1, p.zones.count { it.role == RealFieldRole.SEAT_AVATAR })
        assertEquals(1, p.zones.count { it.role == RealFieldRole.PROFILE_DISMISS })
        assertTrue(p.seatVisualCalibration().isValid())
    }

    @Test fun neverDismissOnTopOfAvatarOrASeatControl() {
        val onAvatar = zone(RealFieldRole.PROFILE_DISMISS, 160, 275, 220, 320)
        val onSeat = zone(RealFieldRole.PROFILE_DISMISS, 180, 300, 240, 340)
        assertFalse(profile(dismiss = onAvatar).valid())
        assertFalse(profile(dismiss = onSeat).valid())
    }

    @Test fun rejectWrongSeatAndBottomActionStripAvatar() {
        assertFalse(profile(avatar =
            zone(RealFieldRole.SEAT_AVATAR, 150, 270, 235, 355, 8)).valid())
        assertFalse(profile(avatar =
            zone(RealFieldRole.SEAT_AVATAR, 150, 900, 235, 985, 0)).valid())
    }

    @Test fun tableDismissCannotHitPopupHalfOrCarryASeatIndex() {
        assertFalse(profile(dismiss =
            zone(RealFieldRole.PROFILE_DISMISS, 680, 470, 760, 530)).valid())
        assertFalse(profile(dismiss =
            zone(RealFieldRole.PROFILE_DISMISS, 245, 470, 285, 520, 0)).valid())
    }

    @Test fun allProfileNavigationTouchesMustBeOptInCalibrations() {
        val p = profile()
        assertFalse(p.zones.any { it.role == RealFieldRole.HERO_BET_RAISE })
        assertFalse(p.zones.any { it.role == RealFieldRole.HERO_FOLD })
    }
}
