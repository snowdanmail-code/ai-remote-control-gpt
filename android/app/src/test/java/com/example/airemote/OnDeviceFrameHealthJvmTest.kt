package com.example.airemote

import org.junit.Assert.*
import org.junit.Test

class OnDeviceFrameHealthJvmTest {
    @Test fun acceptsReadableFreshSignal() {
        val signature = ByteArray(480) { if (it % 3 == 0) 20 else if (it % 3 == 1) 120 else 200.toByte() }
        val result = OnDeviceFrameHealthAnalyzer.assess(signature, 9000, 10000, false, true)
        assertTrue(result.usable)
        assertEquals("signal_visible", result.reason)
    }
    @Test fun rejectsBlockedBlackFrame() {
        val result = OnDeviceFrameHealthAnalyzer.assess(ByteArray(480), 9000, 10000, false, true)
        assertFalse(result.usable)
        assertEquals("near_black", result.reason)
    }
    @Test fun rejectsStaleFrame() {
        val signature = ByteArray(480) { (it % 245).toByte() }
        assertEquals("stale_frame", OnDeviceFrameHealthAnalyzer.assess(signature, 7000, 10000, false, true).reason)
    }
    @Test fun operatorStopAlwaysBlocks() {
        val signature = ByteArray(480) { (it % 245).toByte() }
        assertEquals("operator_stop", OnDeviceFrameHealthAnalyzer.assess(signature, 9900, 10000, true, true).reason)
    }
}
