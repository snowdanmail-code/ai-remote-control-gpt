package com.example.airemote

/** Player is the unchanged default; spectator is an explicit opt-in observation mode. */
enum class CaptureRole { PLAYER, SPECTATOR }

object CaptureObservationMode {
    @Volatile var role: CaptureRole = CaptureRole.PLAYER
        private set

    /**
     * Select ONLY with the current capture inactive and emergency STOP engaged.
     * This is a fresh capture session boundary, not a hot change inside a hand.
     */
    @Synchronized fun select(target: CaptureRole, captureActive: Boolean, stopped: Boolean): Boolean {
        if (captureActive || !stopped) return false
        role = target
        return true
    }

    fun isSpectator(): Boolean = role == CaptureRole.SPECTATOR
}
