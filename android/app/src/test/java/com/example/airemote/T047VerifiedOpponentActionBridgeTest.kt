package com.example.airemote

import org.junit.Assert.*
import org.junit.Test

class T047VerifiedOpponentActionBridgeTest {
    private val session = T046TableSession("com.example.game", 7L, 9L)
    private fun memory(): T046TableOpponentMemory =
        T046TableOpponentMemory().apply {
            beginSession(session, 5)
            observePopup(session, T046PopupObservation(
                2, "screenNick",
                listOf(OpponentPopupMetricValue(OpponentPopupMetric.VPIP, 43.0))))
        }
    private fun action(name: String = "FOLD", key: String = "action_1") =
        ConfirmedOpponentAction(
            tableKey = "table7", handKey = "hand9",
            captureEpoch = 7L, windowGeneration = 9L,
            opponentKey = "trusted_actor_2",
            stableOpponentIdentityVerified = false,
            actionKey = key, street = "PREFLOP", action = name,
            facingBetVerified = true,
            firstFrame = 21L, confirmedFrame = 23L,
            firstCapturedAtMs = 10_000L, confirmedCapturedAtMs = 10_200L,
            inspectedAtMs = 10_300L, sourceVerified = true,
            actionConfirmedByIndependentFrames = true
        )
    private fun proof() = T047SeatActorProof(
        "com.example.game", "table7", "hand9", 7L, 9L, 2,
        "trusted_actor_2", "action_1", 23L,
        seatActorIndependentlyVerified = true,
        sourceIndependentlyVerified = true
    )

    @Test fun confirmedFoldChangesOnlySeatAndLeavesNicknameLocal() {
        val m = memory()
        val result = T047VerifiedOpponentActionBridge.ingest(m, session,
            "table7", action(), proof()) { OpponentEvidenceResult.ACCEPTED }
        assertEquals(T047OpponentActionStatus.ACCEPTED, result)
        assertEquals(T046HandParticipation.FOLDED_CONFIRMED,
            m.snapshot(session).single().participation)
        assertEquals("screenNick", m.snapshot(session).single().nickname)
        val prompt = m.summaryForChatGpt(session, "hand9")
        assertTrue(prompt.contains("folded=seat_2"))
        assertFalse(prompt.contains("screenNick"))
        assertTrue(prompt.contains("roster_unverified=1"))
    }

    @Test fun ocRAndWrongSeatBindingCannotChangeMemoryOrCallHandCollector() {
        val m = memory()
        var calls = 0
        val refuse: (ConfirmedOpponentAction) -> OpponentEvidenceResult = {
            calls++
            OpponentEvidenceResult.ACCEPTED
        }
        assertEquals(T047OpponentActionStatus.UNVERIFIED_BINDING,
            T047VerifiedOpponentActionBridge.ingest(m,session,
                "table7",action(),proof().copy(
                    seatActorIndependentlyVerified = false),refuse))
        assertEquals(T047OpponentActionStatus.WRONG_SESSION_OR_ACTION,
            T047VerifiedOpponentActionBridge.ingest(m,session,
                "table7",action(),proof().copy(
                    windowGeneration = 10L),refuse))
        assertEquals(T047OpponentActionStatus.WRONG_SESSION_OR_ACTION,
            T047VerifiedOpponentActionBridge.ingest(m,session,
                "table7",action(),proof().copy(
                    actionKey = "other_action"),refuse))
        assertEquals(T047OpponentActionStatus.UNKNOWN_OR_AMBIGUOUS_SEAT,
            T047VerifiedOpponentActionBridge.ingest(m,session,
                "table7",action(),proof().copy(seat = 3),refuse))
        assertEquals(0,calls)
        assertEquals(T046HandParticipation.UNKNOWN,
            m.snapshot(session).single().participation)
    }

    @Test fun actualLiveOpponentCollectorMustAcceptEventBeforeSeatChanges() {
        val m = memory()
        val collector = LiveOpponentAwareness()
        val start = VerifiedOpponentHandStart(
            "table7", "hand9", 7L, 9L,
            10L, 11L, 9_100L, 9_300L, 9_400L,
            sourceVerified = true, stableTableAndHandVerified = true)
        assertEquals(OpponentEvidenceResult.ACCEPTED, collector.begin(start))
        assertEquals(T047OpponentActionStatus.ACCEPTED,
            T047VerifiedOpponentActionBridge.ingest(
                m, session, "table7", action(), proof(), collector::observe))
        assertEquals(T046HandParticipation.FOLDED_CONFIRMED,
            m.snapshot(session).single().participation)
        assertEquals(T047OpponentActionStatus.NOT_CONFIRMED_BY_HAND_COLLECTOR,
            T047VerifiedOpponentActionBridge.ingest(
                m, session, "table7", action(), proof(), collector::observe))
    }

    @Test fun aRejectedHandEventMustNotCauseFalseFold() {
        val m=memory()
        val result=T047VerifiedOpponentActionBridge.ingest(m,session,
            "table7",action(),proof()) { OpponentEvidenceResult.REJECTED }
        assertEquals(T047OpponentActionStatus.NOT_CONFIRMED_BY_HAND_COLLECTOR,result)
        assertEquals(T046HandParticipation.UNKNOWN,
            m.snapshot(session).single().participation)
        assertEquals("",m.summaryForChatGpt(session,"hand9"))
    }

    @Test fun acceptedActionRequiresOwnSourceIndependentFrameConfirmation() {
        val m=memory()
        val result=T047VerifiedOpponentActionBridge.ingest(m,session,
            "table7",action().copy(
                actionConfirmedByIndependentFrames = false),
            proof()) { OpponentEvidenceResult.ACCEPTED }
        assertEquals(T047OpponentActionStatus.UNVERIFIED_BINDING,result)
        assertEquals(T046HandParticipation.UNKNOWN,m.snapshot(session).single().participation)
    }

    @Test fun checkDoesNotMarkSeatFoldedAndCannotRefoldWithSameEventKey() {
        val m=memory()
        assertEquals(T047OpponentActionStatus.ACCEPTED,
            T047VerifiedOpponentActionBridge.ingest(m,session,"table7",
                action("CHECK").copy(facingBetVerified = false),
                proof()) { OpponentEvidenceResult.ACCEPTED })
        assertEquals(T046HandParticipation.ACTIVE_CONFIRMED,
            m.snapshot(session).single().participation)
        assertEquals(T047OpponentActionStatus.MEMORY_REJECTED,
            T047VerifiedOpponentActionBridge.ingest(m,session,"table7",
                action(),proof()) { OpponentEvidenceResult.ACCEPTED })
        assertEquals(T046HandParticipation.ACTIVE_CONFIRMED,
            m.snapshot(session).single().participation)
    }

    @Test fun conflictingNicknamesPreventAutomaticLinkToOldPlayer() {
        val m=memory()
        m.observePopup(session,T046PopupObservation(2,"completelyDifferent",
            listOf(OpponentPopupMetricValue(OpponentPopupMetric.VPIP,18.0))))
        val result=T047VerifiedOpponentActionBridge.ingest(m,session,"table7",
            action(),proof()) { OpponentEvidenceResult.ACCEPTED }
        assertEquals(T047OpponentActionStatus.UNKNOWN_OR_AMBIGUOUS_SEAT,result)
    }
}
