package com.example.airemote

import org.junit.Assert.*
import org.junit.Test

class PostHandLearningJvmTest {
    private class TestStore : HandLearningStore {
        val receipts = mutableSetOf<String>()
        var count = 0
        var lastSaved: VerifiedFinishedHand? = null
        override fun commitHandAtomically(hand: VerifiedFinishedHand): HandLearningResult {
            if (!receipts.add(hand.tableKey + "/" + hand.handKey)) return HandLearningResult.ALREADY_SEEN
            lastSaved = hand
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

    private fun transcript(complete: Boolean = true) =
        T054OpponentOpportunityTranscript(
            "verified-player", true, true, complete, complete,
            listOf(T054VerifiedPreflopEvent("posted", T054PreflopEventKind.POST_BLIND, true),
                T054VerifiedPreflopEvent("folded", T054PreflopEventKind.FOLD, true)),
            emptyList()
        )

    @Test fun completeIndependentlyVerifiedPreflopAddsNegativeVpipDenominator() {
        val store = TestStore()
        val full = hand().copy(
            opponents = listOf(OpponentHandFacts("verified-player", true)),
            verifiedOpponentOpportunities = listOf(transcript()))
        assertEquals(HandLearningResult.APPLIED, PostHandLearner(store).onHandFinished(full))
        assertEquals(false, store.lastSaved!!.opponents.single().vpip)
        assertEquals(false, store.lastSaved!!.opponents.single().preflopRaise)
        assertTrue(store.lastSaved!!.verifiedOpponentOpportunities.isEmpty())
    }

    @Test fun incompletePreflopDoesNotInventNegativeVpip() {
        val store = TestStore()
        val partial = hand().copy(
            opponents = listOf(OpponentHandFacts("verified-player", true)),
            verifiedOpponentOpportunities = listOf(transcript(complete = false)))
        assertEquals(HandLearningResult.APPLIED, PostHandLearner(store).onHandFinished(partial))
        assertNull(store.lastSaved!!.opponents.single().vpip)
        assertNull(store.lastSaved!!.opponents.single().preflopRaise)
    }

    @Test fun conflictingAuthenticatedPreflopDoesNotCorruptOpponentHistory() {
        val store = TestStore()
        val inconsistent = hand().copy(
            opponents = listOf(OpponentHandFacts("verified-player", true, vpip = true)),
            verifiedOpponentOpportunities = listOf(transcript()))
        assertEquals(HandLearningResult.REJECTED,
            PostHandLearner(store).onHandFinished(inconsistent))
        assertEquals(0, store.completedHandsCount())
    }

    @Test fun unsignedOrUnlinkedOpportunitiesCannotWriteToHistory() {
        val store = TestStore()
        val missing = hand().copy(
            opponents = listOf(OpponentHandFacts("verified-player", true)),
            verifiedOpponentOpportunities = listOf(
                transcript().copy(sourceIndependentlyVerified = false)))
        assertEquals(HandLearningResult.REJECTED,
            PostHandLearner(store).onHandFinished(missing))
        assertEquals(0, store.completedHandsCount())
        assertEquals(HandLearningResult.REJECTED,
            PostHandLearner(store).onHandFinished(
                missing.copy(verifiedOpponentOpportunities = listOf(
                    transcript().copy(opponentKey = "another-actor")))))
    }

    @Test fun multipleBetFacingEventsCannotOverwriteSingleHandFoldRate() {
        val store = TestStore()
        val dual = transcript().copy(facingBet = listOf(
            T054VerifiedFacingBetDecision("o1", T054FacingBetChoice.CALL, true),
            T054VerifiedFacingBetDecision("o2", T054FacingBetChoice.FOLD, true)
        ))
        val input = hand().copy(
            opponents = listOf(OpponentHandFacts(
                "verified-player", true, foldedWhenFacingBet = true, facedBet = true)),
            verifiedOpponentOpportunities = listOf(dual))
        assertEquals(HandLearningResult.REJECTED,
            PostHandLearner(store).onHandFinished(input))
        assertEquals(0, store.completedHandsCount())
    }

}
