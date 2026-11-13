package com.example.airemote

import org.junit.Assert.*
import org.junit.Test

class T046TableOpponentMemoryTest {
    private val session = T046TableSession("com.example.game", 8L, 3L)
    private val anotherWindow = session.copy(windowGeneration = 4L)
    private fun profile(seat: Int, nick: String = "playerA", value: Double = 43.0) =
        T046PopupObservation(seat, nick, listOf(
            OpponentPopupMetricValue(OpponentPopupMetric.VPIP, value),
            OpponentPopupMetricValue(OpponentPopupMetric.PFR, 17.0)
        ))

    @Test fun nicknameIsStoredLocallyButOnlyAliasIsSentToGpt() {
        val m = T046TableOpponentMemory()
        assertTrue(m.beginSession(session, 5))
        assertTrue(m.observePopup(session, profile(2, "PrivateNick_321")))
        assertTrue(m.observePopup(session, profile(1, "anotherPlayer")))
        assertFalse(m.observePopup(session, profile(5, "hero")))
        assertTrue(m.beginVerifiedHand(session, "hand_01", true))
        assertTrue(m.applyVerifiedEvent(session, "hand_01", "a001", 2,
            T046HandParticipation.ACTIVE_CONFIRMED, T046Evidence.VERIFIED_HAND_EVENT))
        val report = m.summaryForChatGpt(session, "hand_01")
        assertTrue(report.contains("active=seat_2"))
        assertTrue(report.contains("unknown=1"))
        assertFalse(report.contains("PrivateNick"))
        assertEquals("PrivateNick_321", m.snapshot(session)
            .first { it.seat == 2 }.nickname)
        assertTrue(m.snapshot(anotherWindow).isEmpty())
        assertTrue(m.summaryForChatGpt(anotherWindow, "hand_01").isEmpty())
    }

    @Test fun foldIsNotLeavingTableAndCannotReactivateUntilNextVerifiedHand() {
        val m = T046TableOpponentMemory()
        m.beginSession(session, 5)
        m.observePopup(session, profile(2))
        m.beginVerifiedHand(session, "one", true)
        assertTrue(m.applyVerifiedEvent(session, "one", "a", 2,
            T046HandParticipation.ACTIVE_CONFIRMED, T046Evidence.VERIFIED_HAND_EVENT))
        assertTrue(m.applyVerifiedEvent(session, "one", "b", 2,
            T046HandParticipation.FOLDED_CONFIRMED, T046Evidence.VERIFIED_HAND_EVENT))
        assertTrue(m.summaryForChatGpt(session, "one").contains("folded=seat_2"))
        assertFalse(m.applyVerifiedEvent(session, "one", "c", 2,
            T046HandParticipation.ACTIVE_CONFIRMED, T046Evidence.VERIFIED_HAND_EVENT))
        assertTrue(m.beginVerifiedHand(session, "two", true))
        assertTrue(m.summaryForChatGpt(session, "two").contains("unknown=1"))
    }

    @Test fun ocrFoldAndMissingSeatDoNotChangeHandParticipants() {
        val m = T046TableOpponentMemory()
        m.beginSession(session, 5)
        m.observePopup(session, profile(2))
        assertFalse(m.beginVerifiedHand(session, "hand", false))
        assertTrue(m.summaryForChatGpt(session, "hand").isEmpty())
        assertTrue(m.beginVerifiedHand(session, "hand", true))
        assertFalse(m.applyVerifiedEvent(session, "hand", "fold1", 2,
            T046HandParticipation.FOLDED_CONFIRMED, T046Evidence.OCR_ONLY))
        assertFalse(m.applyVerifiedEvent(session, "hand", "leave1", 2,
            T046HandParticipation.LEFT_TABLE_CONFIRMED, T046Evidence.VERIFIED_HAND_EVENT))
        assertFalse(m.applyVerifiedEvent(session, "hand", "act", 7,
            T046HandParticipation.ACTIVE_CONFIRMED, T046Evidence.VERIFIED_HAND_EVENT))
        assertEquals("active=;folded=;left=;unknown=1",
            m.summaryForChatGpt(session, "hand"))
    }

    @Test fun verifiedTableExitRemovesPlayerAcrossHandsButNotNicknameHistory() {
        val m = T046TableOpponentMemory()
        m.beginSession(session, 5)
        m.observePopup(session, profile(1,"looseCaller"))
        m.beginVerifiedHand(session,"h1",true)
        assertTrue(m.applyVerifiedEvent(session,"h1","exit_1",1,
            T046HandParticipation.LEFT_TABLE_CONFIRMED,
            T046Evidence.VERIFIED_TABLE_EXIT))
        assertTrue(m.beginVerifiedHand(session,"h2",true))
        assertEquals(T046HandParticipation.LEFT_TABLE_CONFIRMED,
            m.snapshot(session).first().participation)
        assertEquals("looseCaller",m.snapshot(session).first().nickname)
    }

    @Test fun conflictingNicknameNeverSilentlyTransfersOldStatistics() {
        val m = T046TableOpponentMemory()
        m.beginSession(session, 5)
        assertTrue(m.observePopup(session,profile(1,"oldNick")))
        assertTrue(m.observePopup(session,profile(1,"newNick")))
        val seat=m.snapshot(session).single()
        assertTrue(seat.ambiguousIdentity)
        assertNull(seat.nickname)
        assertTrue(seat.popupStatistics.isEmpty())
        m.beginVerifiedHand(session,"h1",true)
        assertFalse(m.applyVerifiedEvent(session,"h1","ev",1,
            T046HandParticipation.ACTIVE_CONFIRMED,T046Evidence.VERIFIED_HAND_EVENT))
        assertTrue(m.summaryForChatGpt(session,"h1").contains("unknown=1"))
    }

    @Test fun staleSessionAndRepeatedEventNeverBecomeProof() {
        val m = T046TableOpponentMemory()
        m.beginSession(session,5)
        assertFalse(m.observePopup(anotherWindow,profile(1)))
        assertTrue(m.observePopup(session,profile(1)))
        m.beginVerifiedHand(session,"h1",true)
        assertFalse(m.applyVerifiedEvent(anotherWindow,"h1","ev",1,
            T046HandParticipation.ACTIVE_CONFIRMED,T046Evidence.VERIFIED_HAND_EVENT))
        assertTrue(m.applyVerifiedEvent(session,"h1","ev",1,
            T046HandParticipation.ACTIVE_CONFIRMED,T046Evidence.VERIFIED_HAND_EVENT))
        assertFalse(m.applyVerifiedEvent(session,"h1","ev",1,
            T046HandParticipation.FOLDED_CONFIRMED,T046Evidence.VERIFIED_HAND_EVENT))
        assertFalse(m.applyVerifiedEvent(session,"h_old","ev2",1,
            T046HandParticipation.FOLDED_CONFIRMED,T046Evidence.VERIFIED_HAND_EVENT))
    }
}
