package com.example.airemote

import org.junit.Assert.*
import org.junit.Test

class T048SeatRoundVisualFusionTest {
    private fun sample(
        seq: Long, timeMs: Long,
        seatBets: Map<Int, Long> = emptyMap(),
        stacks: Map<Int, Long> = emptyMap(),
        backs: Map<Int, Boolean> = emptyMap(),
        badges: Map<Int, SeatCueAction> = emptyMap(),
        pot: Long? = null, board: Int? = null,
        epoch: Long = 7, window: Long = 4
    ) = T048SeatFrame(
        OcrFrameTicket(epoch, seq, timeMs, "com.example.game", window),
        1080, 2340, seatBets, stacks, backs, badges, pot, board
    )

    @Test fun clipOneRaiseIsBoundToLeftSeatWagerNotCentralPot() {
        val fusion = T048SeatSignalFusion()
        val first = sample(10, 10_000, seatBets=mapOf(1 to 2L,2 to 2L),
            backs=mapOf(1 to true,2 to true), pot=15, board=0)
        val after = sample(11, 12_000, seatBets=mapOf(1 to 8L,2 to 2L),
            backs=mapOf(1 to true,2 to true),
            badges=mapOf(1 to SeatCueAction.RAISE),pot=15,board=0)
        assertEquals(T048ReadingStatus.WAITING, fusion.observe(first,10_100).status)
        val result = fusion.observe(after,12_100)
        assertEquals(T048ReadingStatus.OBSERVED,result.status)
        assertEquals(T048CueKind.RAISE_WITH_SEAT_CHIPS,result.combined.single().kind)
        assertEquals(1,result.combined.single().seat)
        assertEquals(8L,result.combined.single().amountAfterMinor)
        assertEquals(15L,result.potMinor)
        assertFalse(result.authorizesGameplay)
        assertFalse(result.canUpdateOpponentMemory)
    }

    @Test fun clipOneFoldRequiresLabelAndCardsDisappearingTogether() {
        val fusion=T048SeatSignalFusion()
        fusion.observe(sample(1, 10_000, seatBets=mapOf(3 to 2L),
            backs=mapOf(3 to true),pot=15L), 10_100)
        val observed=fusion.observe(sample(2, 15_900,
            seatBets=mapOf(3 to 2L), backs=mapOf(3 to false),
            badges=mapOf(3 to SeatCueAction.FOLD),pot=15L),16_000)
        assertEquals(T048CueKind.FOLD_WITH_BACKS_DISAPPEARING,
            observed.combined.single().kind)
        assertEquals(3,observed.combined.single().seat)
        assertFalse(observed.provesOpponentAction)
    }

    @Test fun changeInCardBacksAloneIsNotFoldProof() {
        val fusion=T048SeatSignalFusion()
        fusion.observe(sample(1,1000L,backs=mapOf(1 to true)),1100L)
        val observed=fusion.observe(sample(2,2000L,
            backs=mapOf(1 to false)),2100L)
        assertEquals(T048ReadingStatus.INCONCLUSIVE,observed.status)
        assertTrue(observed.combined.isEmpty())
    }

    @Test fun raiseWordWithoutIncreasedSeatWagerCannotBeAttributed() {
        val fusion=T048SeatSignalFusion()
        fusion.observe(sample(1,1000L,seatBets=mapOf(1 to 2L),
            backs=mapOf(1 to true)),1100L)
        val observed=fusion.observe(sample(2,2000L,
            seatBets=mapOf(1 to 2L),backs=mapOf(1 to true),
            badges=mapOf(1 to SeatCueAction.RAISE)),2100L)
        assertEquals(T048ReadingStatus.INCONCLUSIVE,observed.status)
    }

    @Test fun unknownCardsCannotBeSilentlyPromotedToKnownRaise() {
        val fusion=T048SeatSignalFusion()
        fusion.observe(sample(1,1000L,seatBets=mapOf(1 to 2L)),1100L)
        val observed=fusion.observe(sample(2,2000L,
            seatBets=mapOf(1 to 8L),
            badges=mapOf(1 to SeatCueAction.RAISE)),2100L)
        assertTrue(observed.combined.isEmpty())
    }

    @Test fun collectionToFlopIsDiagnosticAndDoesNotDoubleCountPot() {
        val fusion=T048SeatSignalFusion()
        fusion.observe(sample(1,1000L,
            seatBets=mapOf(1 to 8L,2 to 2L,3 to 2L),
            pot=15L,board=0),1100L)
        val after=fusion.observe(sample(2,3000L,
            seatBets=emptyMap(),pot=26L,board=3),3100L)
        val collected=after.combined.single()
        assertEquals(T048CueKind.BETS_MOVING_TO_CENTER,collected.kind)
        assertEquals(15L,collected.amountBeforeMinor)
        assertEquals(26L,collected.amountAfterMinor)
        // Earlier Bank 0.15 already includes at least some visible chips.
        // Never calculate 0.15 + 0.08 + 0.02 etc as a new pot.
        assertEquals(26L,after.potMinor)
    }

    @Test fun staleOrCrossWindowFramesCannotBeCorrelated() {
        val fusion=T048SeatSignalFusion()
        fusion.observe(sample(1,1000L,backs=mapOf(3 to true)),1100L)
        val changed=fusion.observe(sample(2,2000L,
            backs=mapOf(3 to false),badges=mapOf(3 to SeatCueAction.FOLD),
            window=5),2100L)
        assertEquals(T048ReadingStatus.STALE_OR_CHANGED,changed.status)
        assertTrue(changed.combined.isEmpty())
        val late=fusion.observe(sample(3, 3000L,
            backs=mapOf(3 to false),badges=mapOf(3 to SeatCueAction.FOLD),
            window=5),6000L)
        assertEquals(T048ReadingStatus.STALE_OR_CHANGED,late.status)
    }

    @Test fun coloredCardBackClassifierExcludesTealAvatarsAndOtherTokens() {
        assertTrue(T048CardBackColor.isBluePixel(0xFF1675CA.toInt()))
        assertFalse(T048CardBackColor.isBluePixel(0xFF379A98.toInt()))
        assertFalse(T048CardBackColor.isBluePixel(0xFF999999.toInt()))
        assertFalse(T048CardBackColor.isBluePixel(0xFFE20D21.toInt()))
        assertEquals(true,T048CardBackColor.evidence(60,200))
        assertEquals(false,T048CardBackColor.evidence(5,200))
        assertNull(T048CardBackColor.evidence(25,200))
        assertNull(T048CardBackColor.evidence(10,100))
    }
}
