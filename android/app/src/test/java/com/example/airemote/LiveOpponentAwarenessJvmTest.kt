package com.example.airemote

import org.junit.Assert.*
import org.junit.Test

/** Android Gradle/JUnit coverage; JVM scenario suite is also runnable without SDK. */
class LiveOpponentAwarenessJvmTest {
    private fun start() = VerifiedOpponentHandStart("table-verified", "hand-verified", 7, 4,
        100, 101, 1000, 1150, 1200, true, true)
    private fun confirmed(id: String, number: Long, at: Long,
        player: String = "session-1/player-verified", known: Boolean = true) = ConfirmedOpponentAction(
        "table-verified", "hand-verified", 7, 4, player, known, id, "PREFLOP", "RAISE", true,
        number, number + 1, at, at + 150, at + 170, true, true)
    private fun receipt() = VerifiedFinishedHand("table-verified", "hand-verified", 7, 100, 130,
        5000, true, true, emptyList())

    @Test fun positiveFactsAreAttachedButNotPersisted() {
        val observer = LiveOpponentAwareness()
        assertEquals(OpponentEvidenceResult.ACCEPTED, observer.begin(start()))
        assertEquals(OpponentEvidenceResult.ACCEPTED, observer.observe(confirmed("a", 102, 1300)))
        assertEquals(OpponentEvidenceResult.DUPLICATE, observer.observe(confirmed("a", 102, 1300)))
        val fact = observer.prepareForVerifiedTerminal(receipt(), 4)!!.opponents.single()
        assertTrue(fact.identityVerified)
        assertEquals(true, fact.vpip)
        assertEquals(true, fact.preflopRaise)
        assertNull(fact.showdown)
        assertNull(fact.foldedWhenFacingBet)
    }

    @Test fun preventsCrossWindowAndCrossHandReuse() {
        val observer = LiveOpponentAwareness()
        observer.begin(start())
        assertNull(observer.prepareForVerifiedTerminal(receipt(), 5))
        assertNull(observer.prepareForVerifiedTerminal(receipt().copy(handKey = "other"), 4))
        assertEquals(OpponentEvidenceResult.INVALIDATED,
            observer.observe(confirmed("a", 102, 1300).copy(windowGeneration = 5)))
        assertTrue(observer.snapshot().isEmpty())
    }

    @Test fun neverMergesUnverifiedSeatAcrossFinishedHands() {
        val observer = LiveOpponentAwareness()
        observer.begin(start())
        observer.observe(confirmed("a", 102, 1300, "seat-2", false))
        assertFalse(observer.prepareForVerifiedTerminal(receipt(), 4)!!.opponents.single().identityVerified)
    }

    @Test fun resetLeavesPersistentStoreUntouchedAndPreventsFutureProposals() {
        val observer = LiveOpponentAwareness()
        observer.begin(start())
        observer.observe(confirmed("a", 102, 1300))
        observer.reset()
        assertNull(observer.prepareForVerifiedTerminal(receipt(), 4))
        assertEquals(OpponentEvidenceResult.REJECTED, observer.observe(confirmed("b", 104, 1600)))
    }
}
