package com.example.airemote

import org.junit.Assert.*
import org.junit.Test

class PostHandLearningJvmTest {
    private class TestStore : HandLearningStore {
        val receipts = mutableSetOf<String>()
        var count = 0
        override fun commitHandAtomically(hand: VerifiedFinishedHand): HandLearningResult {
            if (!receipts.add(hand.tableKey + "/" + hand.handKey)) return HandLearningResult.ALREADY_SEEN
            count++
            return HandLearningResult.APPLIED
        }
        override fun opponentProfile(opponentKey: String): OpponentLearningSummary? = null
        override fun completedHandsCount(): Int = count
    }
    private fun hand() = VerifiedFinishedHand(
        "t", "session-1-hand-1", 1, 2, 3, 1000, true, true, emptyList(), heroNetChips=null)

    @Test fun acceptsConfirmedTerminalImmediately() {
        val store = TestStore()
        assertEquals(HandLearningResult.APPLIED, PostHandLearner(store).onHandFinished(hand()))
        assertEquals(1, store.completedHandsCount())
    }
    @Test fun rejectsSingleUnconfirmedTerminalFrame() {
        val store = TestStore()
        assertEquals(HandLearningResult.REJECTED, PostHandLearner(store).onHandFinished(
            hand().copy(terminalConfirmedByIndependentFrames=false)))
        assertEquals(0, store.completedHandsCount())
    }
    @Test fun rejectsUnverifiedSource() {
        assertEquals(HandLearningResult.REJECTED, PostHandLearner(TestStore()).onHandFinished(
            hand().copy(sourceVerified=false)))
    }
    @Test fun duplicateHandNeverDoubleCounts() {
        val store = TestStore()
        val l = PostHandLearner(store)
        assertEquals(HandLearningResult.APPLIED, l.onHandFinished(hand()))
        assertEquals(HandLearningResult.ALREADY_SEEN, l.onHandFinished(hand()))
        assertEquals(1, store.completedHandsCount())
    }
    @Test fun incompleteOpponentIdentityStillAllowsOwnHandLog() {
        assertEquals(HandLearningResult.APPLIED, PostHandLearner(TestStore()).onHandFinished(
            hand().copy(opponents=listOf(OpponentHandFacts("seat-2", false)))))
    }
}
