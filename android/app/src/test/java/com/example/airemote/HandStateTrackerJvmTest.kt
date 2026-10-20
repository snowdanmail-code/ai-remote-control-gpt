package com.example.airemote

import org.junit.Assert.*
import org.junit.Test

class HandStateTrackerJvmTest {
    private val hole = listOf("Ah", "Kd")

    private fun observation(seq: Long, board: List<String> = emptyList(),
                            epoch: Long = 1L, source: Boolean = true,
                            age: Long = 100L, cards: List<String> = hole,
                            table: String = "verified-table"): VerifiedTableObservation =
        VerifiedTableObservation(table, epoch, seq, seq*400L, seq*400L+age, source, .99, cards, board)

    @Test fun `two confirmations are required per new street`() {
        val t = HandStateTracker()
        assertFalse(t.ingest(observation(1)).trustworthyNow)
        assertEquals(HandStage.PREFLOP, t.ingest(observation(2)).stage)
        assertFalse(t.ingest(observation(3, listOf("2c", "3h", "4d"))).trustworthyNow)
        val flop = t.ingest(observation(4, listOf("2c", "3h", "4d")))
        assertEquals(HandStage.FLOP, flop.stage)
        assertTrue(flop.trustworthyNow)
    }

    @Test fun `duplicate card invalidates state`() {
        val t = HandStateTracker()
        t.ingest(observation(1)); t.ingest(observation(2))
        val bad = t.ingest(observation(3, listOf("Ah", "3h", "4d")))
        assertFalse(bad.trustworthyNow)
        assertEquals(HandStage.PREFLOP, bad.stage)
    }

    @Test fun `a new capture invalidates the previous hand`() {
        val t = HandStateTracker()
        t.ingest(observation(1)); t.ingest(observation(2))
        val change = t.ingest(observation(3, epoch=2))
        assertEquals(HandStage.UNKNOWN, change.stage)
        assertFalse(change.trustworthyNow)
    }

    @Test fun `unverified source never confirms`() {
        val t = HandStateTracker()
        assertFalse(t.ingest(observation(1, source=false)).trustworthyNow)
        assertFalse(t.ingest(observation(2, source=false)).trustworthyNow)
        assertEquals(HandStage.UNKNOWN, t.snapshot().stage)
    }

    @Test fun `context expires without fresh frames`() {
        val t = HandStateTracker()
        t.ingest(observation(1)); t.ingest(observation(2))
        assertTrue(t.snapshot(nowMs=1000).trustworthyNow)
        assertFalse(t.snapshot(nowMs=3000).trustworthyNow)
    }
}
