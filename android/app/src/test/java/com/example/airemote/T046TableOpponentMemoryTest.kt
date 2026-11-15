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
        assertFalse(m.applyVerifiedEvent(session, "one", "d", 2,
            T046HandParticipation.FOLDED_CONFIRMED, T046Evidence.VERIFIED_HAND_EVENT))
        assertTrue(m.applyVerifiedEvent(session, "one", "leave_after_fold", 2,
            T046HandParticipation.LEFT_TABLE_CONFIRMED,
            T046Evidence.VERIFIED_TABLE_EXIT))
        assertTrue(m.beginVerifiedHand(session, "two", true))
        // Player left the TABLE (not just the hand); subsequent hands
        // must not reclassify this known absence as an unknown participant.
        val afterLeave = m.summaryForChatGpt(session, "two")
        assertTrue(afterLeave.contains("left=seat_2"))
        assertTrue(afterLeave.contains("unknown=0"))
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
        assertEquals("active=;folded=;left=;unknown=1;roster_unverified=1;unknown_seats=seat_2",
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

    @Test fun verifiedSeatReplacementInvalidatesPreviousNicknameAndStats() {
        val m = T046TableOpponentMemory()
        m.beginSession(session,5)
        m.observePopup(session,profile(2,"oldPlayer",43.0))
        m.beginVerifiedHand(session,"h1",true)
        val original = m.snapshot(session).single()
        assertFalse(m.confirmedSeatTurnover(session,2,"replace1",false))
        assertFalse(m.confirmedSeatTurnover(anotherWindow,2,"replace1",true))
        assertTrue(m.confirmedSeatTurnover(session,2,"replace1",true))
        val after = m.snapshot(session).single()
        assertNotEquals(original.sessionAlias, after.sessionAlias)
        assertNull(after.nickname)
        assertTrue(after.popupStatistics.isEmpty())
        assertEquals(T046HandParticipation.UNKNOWN,after.participation)
        assertFalse(m.confirmedSeatTurnover(session,2,"replace1",true))
    }

    @Test fun unknownSeatIdsAppearButNeverClaimFoldFromUnverifiedOcr() {
        val m = T046TableOpponentMemory()
        m.beginSession(session, 5)
        m.observePopup(session, profile(1, "nameOne"))
        m.observePopup(session, profile(2, "nameTwo"))
        m.beginVerifiedHand(session, "verified-h", true)
        assertFalse(m.applyVerifiedEvent(session, "verified-h", "ocrOnly", 1,
            T046HandParticipation.FOLDED_CONFIRMED, T046Evidence.OCR_ONLY))
        val text = m.summaryForChatGpt(session, "verified-h")
        assertTrue(text.contains("unknown=2"))
        assertTrue(text.contains("unknown_seats=seat_1,seat_2"))
        assertTrue(text.contains("folded="))
        assertFalse(text.contains("nameOne"))
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
