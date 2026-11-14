package com.example.airemote

import org.junit.Assert.*
import org.junit.Test

class T051LabMachineTest {
    @Test fun realAndroidCallbackAloneDoesNotProveAction() {
        val m = T051LabMachine()
        val t = m.start(T051LabScenario.CHECK_OK, 1_000L)
        assertTrue(m.review(t, true, "ACCEPTABLE_CANDIDATE_NOT_A_GESTURE"))
        assertTrue(m.dispatched(t))
        m.gestureResult(t, true)
        assertEquals(T051LabPhase.WAITING_RECEIPT, m.currentPhase())
        assertFalse(m.report().reason.startsWith("PASS"))
        assertTrue(m.appReceipt(t, "CHECK"))
        assertEquals(T051LabPhase.CONFIRMED, m.currentPhase())
    }

    @Test fun uiReceiptBeforeOsCallbackRequiresBoth() {
        val m = T051LabMachine()
        val t = m.start(T051LabScenario.CHECK_OK, 1_000L)
        assertTrue(m.review(t, true, "accepted"))
        assertTrue(m.dispatched(t))
        assertTrue(m.appReceipt(t, "CHECK"))
        assertEquals(T051LabPhase.WAITING_ANDROID, m.currentPhase())
        m.gestureResult(t, true)
        assertEquals(T051LabPhase.CONFIRMED, m.currentPhase())
        assertFalse(m.appReceipt(t, "CHECK"))
    }

    @Test fun rejectedRaiseCannotStartAndroidGesture() {
        val m = T051LabMachine()
        val t = m.start(T051LabScenario.RAISE_REJECTED, 1_000L)
        assertFalse(m.review(t, false, "NOT_LEGAL"))
        assertEquals(T051LabPhase.BLOCKED, m.currentPhase())
        assertFalse(m.dispatched(t))
        m.gestureResult(t, true)
        assertEquals(T051LabPhase.BLOCKED, m.currentPhase())
    }

    @Test fun neverTreatMissingReceiptAsSuccessOrRetry() {
        val m = T051LabMachine(timeoutMs = 2_000L)
        val t = m.start(T051LabScenario.MISSING_RECEIPT, 1_000L)
        assertTrue(m.review(t, true, "accepted"))
        assertTrue(m.dispatched(t))
        m.gestureResult(t, true)
        assertFalse(m.appReceipt(t, "CHECK"))
        m.tick(3_001L)
        assertEquals(T051LabPhase.TIMED_OUT, m.currentPhase())
        assertFalse(m.dispatched(t))
    }

    @Test fun canceledAndroidGestureAndWrongActionFail() {
        val m = T051LabMachine()
        val t = m.start(T051LabScenario.CHECK_OK, 1_000L)
        assertTrue(m.review(t, true, "accepted"))
        assertTrue(m.dispatched(t))
        assertFalse(m.appReceipt(t, "RAISE"))
        m.gestureResult(t, false)
        assertEquals(T051LabPhase.FAILED, m.currentPhase())
    }

    @Test fun stopRevokesAsyncCallbacksAndOldTickets() {
        val m = T051LabMachine()
        val t = m.start(T051LabScenario.CHECK_OK, 1_000L)
        m.review(t, true, "accepted")
        m.dispatched(t)
        m.stop()
        assertEquals(T051LabPhase.STOPPED, m.currentPhase())
        m.gestureResult(t, true)
        assertFalse(m.appReceipt(t, "CHECK"))
        assertEquals(T051LabPhase.STOPPED, m.currentPhase())
        assertTrue(m.report().reason.contains("STOP"))
    }

    @Test fun repeatedSessionMakesOldProofUnusable() {
        val m = T051LabMachine()
        val old = m.start(T051LabScenario.CHECK_OK, 1_000L)
        m.review(old, true, "accepted")
        val fresh = m.start(T051LabScenario.CHECK_OK, 1_100L)
        assertNotEquals(old, fresh)
        assertFalse(m.dispatched(old))
        assertTrue(m.review(fresh, true, "accepted"))
        assertEquals(T051LabPhase.READY_TO_TAP, m.currentPhase())
    }

    @Test fun sandboxUsesActualT043ContractButScriptedModelOutput() {
        val ok = T051LabContractFixture.evaluate(5_000L, T051LabScenario.CHECK_OK)
        assertTrue("prompt missing", ok.promptCreated)
        assertTrue(ok.reason, ok.accepted)
        val bad = T051LabContractFixture.evaluate(5_000L, T051LabScenario.RAISE_REJECTED)
        assertTrue(bad.promptCreated)
        assertFalse(bad.accepted)
        assertEquals("NOT_LEGAL", bad.reason)
    }
}
