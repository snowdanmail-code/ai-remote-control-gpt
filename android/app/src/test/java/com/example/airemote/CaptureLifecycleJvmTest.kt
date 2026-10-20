package com.example.airemote

import org.junit.Assert.*
import org.junit.Test

class CaptureLifecycleJvmTest {
    @Test fun preventsDoubleStartAndTeardownRace() {
        val gate = CaptureLifecycleGuard()
        assertTrue(gate.beginStart())
        assertFalse(gate.beginStart())
        assertTrue(gate.markActive())
        gate.requestStop()
        assertFalse(gate.mayRequestNewCapture())
        assertFalse(gate.markActive())
        assertFalse(gate.beginStart())
        gate.completeStop()
        assertTrue(gate.mayRequestNewCapture())
        assertTrue(gate.beginStart())
    }
}
