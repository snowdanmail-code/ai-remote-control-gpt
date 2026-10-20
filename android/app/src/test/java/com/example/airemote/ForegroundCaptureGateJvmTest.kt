package com.example.airemote

import org.junit.Assert.*
import org.junit.Test

class ForegroundCaptureGateJvmTest {
    @Test fun startsClosed() {
        val gate = ForegroundCaptureGate()
        assertNull(gate.acquireTicket())
        assertFalse(gate.canPublish(null))
    }

    @Test fun permitsBackgroundButInvalidatesInFlightOnReturn() {
        val gate = ForegroundCaptureGate()
        gate.onActivityHidden()
        val ticket = gate.acquireTicket()
        assertTrue(gate.canPublish(ticket))
        gate.onActivityVisible()
        assertNull(gate.acquireTicket())
        assertFalse(gate.canPublish(ticket))
    }

    @Test fun oldBackgroundTicketNeverRevalidates() {
        val gate = ForegroundCaptureGate()
        gate.onActivityHidden()
        val old = gate.acquireTicket()
        gate.onActivityVisible()
        gate.onActivityHidden()
        assertFalse(gate.canPublish(old))
        assertTrue(gate.canPublish(gate.acquireTicket()))
    }
}
