package com.example.airemote

import org.junit.Assert.*
import org.junit.Test

class OneWinSeatedHeroEvidenceTest {
    private fun observation(
        sequence: Long, time: Long, window: Long = 3L,
        cards: List<String> = listOf("AH","KD"),
        cardTime: Long = time-100L,
        cardEpoch: Long = 7L
    ) = OneWinHeroAfterBuyInFrame(
        "com.example.game", 7L, window, sequence, time,
        cardEpoch, cardTime, cards)

    @Test fun disappearingBuyInDialogIsNotEvidenceOfSeating() {
        val gate = OneWinBottomHeroAfterBuyInAgreement()
        assertFalse(gate.observe(null, 1_000L,
            buyInPreviouslyVerified = true, dialogStillVisible = false))
        assertFalse(gate.observe(observation(1,1_000L), 1_100L,
            buyInPreviouslyVerified = false, dialogStillVisible = false))
        assertFalse(gate.observe(observation(2,2_000L), 2_100L,
            buyInPreviouslyVerified = true, dialogStillVisible = true))
    }

    @Test fun twoFreshIdenticalBottomHoleReadingsAfterBuyInConfirmSeating() {
        val gate = OneWinBottomHeroAfterBuyInAgreement()
        assertFalse(gate.observe(observation(1,1_000L), 1_100L,
            buyInPreviouslyVerified = true, dialogStillVisible = false))
        assertTrue(gate.observe(observation(2,2_600L), 2_700L,
            buyInPreviouslyVerified = true, dialogStillVisible = false))
    }

    @Test fun windowChangeOrDifferentCardsResetAgreement() {
        val gate = OneWinBottomHeroAfterBuyInAgreement()
        assertFalse(gate.observe(observation(1,1_000L), 1_100L, true, false))
        assertFalse(gate.observe(observation(2,2_500L,window=4L), 2_600L, true,false))
        assertFalse(gate.observe(observation(3,4_000L,
            window=4L,cards=listOf("2C","3D")), 4_100L,true,false))
        assertTrue(gate.observe(observation(4,5_500L,
            window=4L,cards=listOf("2C","3D")), 5_600L,true,false))
    }

    @Test fun staleAndDuplicateCardEvidenceCanNeverConfirm() {
        val gate = OneWinBottomHeroAfterBuyInAgreement()
        val stale = observation(1,1000L, cardTime=100L)
        assertFalse(gate.observe(stale, 1_200L, true,false))
        val sameCard = observation(2,2_000L,cards=listOf("AH","AH"))
        assertFalse(gate.observe(sameCard,2_100L,true,false))
        val wrongEpoch = observation(3,3_000L,cardEpoch=1L)
        assertFalse(gate.observe(wrongEpoch,3_100L,true,false))
        assertFalse(gate.observe(observation(4,4_000L), 8_000L,true,false))
    }

    @Test fun badScreenOrExpiredFrameCannotBeReusedToTriggerSeated() {
        val gate = OneWinBottomHeroAfterBuyInAgreement()
        assertFalse(gate.observe(observation(1,1_000L), 1_100L,true,false))
        assertFalse(gate.observe(observation(2,1_000L), 1_200L,true,false))
        assertFalse(gate.observe(observation(3,9_000L), 9_100L,true,false))
        assertFalse(gate.observe(observation(4,10_000L), 10_100L,true,true))
        assertFalse(gate.observe(observation(5,11_000L), 11_100L,true,false))
    }
}
