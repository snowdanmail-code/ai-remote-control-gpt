package com.example.airemote

import org.junit.Assert.*
import org.junit.Test

class T050TransientBadgePulseTest {
    private fun frame(seq:Long, at:Long, tones:List<T050SeatTone>,
                      epoch:Long=7, window:Long=2) = T050BadgeFrame(
        "com.example.game",epoch,window,seq,at,tones)

    private fun badge(seat:Int, tone:T050BadgeTone, strength:Double =
        if (tone == T050BadgeTone.NONE) 0.0 else 0.45
    ) = T050SeatTone(seat,tone,strength)

    @Test fun independentlyTracksTwoSimultaneousSeatBadges() {
        val m = T050TransientBadgePulseTracker()
        assertTrue(m.observe(frame(1,1000L,listOf(
            badge(1,T050BadgeTone.GREEN),
            badge(4,T050BadgeTone.LIGHT))),1100L).isEmpty())
        val hits=m.observe(frame(2,1330L,listOf(
            badge(1,T050BadgeTone.GREEN),
            badge(4,T050BadgeTone.LIGHT))),1400L)
        assertEquals(2,hits.size)
        assertEquals(setOf(1,4),hits.map { it.seat }.toSet())
        assertTrue(hits.all { !it.confirmsAction && !it.authorizesTouch })
        assertTrue(m.observe(frame(3,1660L,listOf(
            badge(1,T050BadgeTone.GREEN),
            badge(4,T050BadgeTone.LIGHT))),1700L).isEmpty())
    }

    @Test fun oneMissingFrameDoesNotAllowDuplicatePulse() {
        val m=T050TransientBadgePulseTracker()
        m.observe(frame(1,1000,listOf(badge(2,T050BadgeTone.RED))),1050)
        assertEquals(1,m.observe(frame(2,1330,listOf(
            badge(2,T050BadgeTone.RED))),1400).size)
        m.observe(frame(3,1660,emptyList()),1700)
        assertTrue(m.observe(frame(4,1990,listOf(
            badge(2,T050BadgeTone.RED))),2030).isEmpty())
        m.observe(frame(5,2320,emptyList()),2400)
        m.observe(frame(6,2650,emptyList()),2700)
        m.observe(frame(7,2980,listOf(badge(2,T050BadgeTone.RED))),3030)
        assertEquals(1,m.observe(frame(8,3310,listOf(
            badge(2,T050BadgeTone.RED))),3360).size)
    }

    @Test fun windowChangeAndOldFramesCannotReusePreviousBadge() {
        val m=T050TransientBadgePulseTracker()
        m.observe(frame(1,1000,listOf(badge(1,T050BadgeTone.GREEN))),1100)
        assertTrue(m.observe(frame(2,1330,listOf(
            badge(1,T050BadgeTone.GREEN)),window=3),1400).isEmpty())
        assertEquals(1,m.observe(frame(3,1660,listOf(
            badge(1,T050BadgeTone.GREEN)),window=3),1700).size)
        assertTrue(m.observe(frame(4,2000,listOf(
            badge(1,T050BadgeTone.GREEN)),window=3),3000).isEmpty())
    }

    @Test fun realVideoWhiteAvatarBaselineThenBrighterFoldTriggersNewPulse() {
        val m = T050TransientBadgePulseTracker()
        // Measured in the owner's 1080x2340 video: the BOTS avatar has
        // ~0.17 light fraction even when no FOLD badge is present.
        m.observe(frame(1,1000L,listOf(
            badge(2,T050BadgeTone.LIGHT,0.173))),1050L)
        assertTrue(m.observe(frame(2,1330L,listOf(
            badge(2,T050BadgeTone.LIGHT,0.173))),1400L).isEmpty())
        // The separate white FOLD label increases it to ~0.54;
        // only a second independent high-intensity frame confirms pulse.
        assertTrue(m.observe(frame(3,1660L,listOf(
            badge(2,T050BadgeTone.LIGHT,0.539))),1700L).isEmpty())
        val result = m.observe(frame(4,1990L,listOf(
            badge(2,T050BadgeTone.LIGHT,0.532))),2020L)
        assertEquals(1,result.size)
        assertEquals(2,result.single().seat)
        assertFalse(result.single().confirmsAction)
    }

    @Test fun invalidFractionOrOverlyStaleFrameResetsPulseState() {
        val m=T050TransientBadgePulseTracker()
        assertTrue(m.observe(frame(1,1000,
            listOf(badge(1,T050BadgeTone.GREEN,Double.NaN))),1100).isEmpty())
        assertTrue(m.observe(frame(2,1800,
            listOf(badge(1,T050BadgeTone.GREEN))),1900).isEmpty())
        assertTrue(m.observe(frame(3,2500,
            listOf(badge(1,T050BadgeTone.GREEN))),2800).isEmpty())
    }

    @Test fun noActionCanBeInferredFromBareColoredPixels() {
        assertEquals(T050BadgeTone.GREEN,T050BadgePixelTone.tone(20,180,35))
        assertEquals(T050BadgeTone.LIGHT,T050BadgePixelTone.tone(218,222,220))
        assertEquals(T050BadgeTone.RED,T050BadgePixelTone.tone(220,30,35))
        assertEquals(T050BadgeTone.NONE,T050BadgePixelTone.tone(50,50,50))
        assertEquals(T050BadgeTone.NONE,T050BadgePixelTone.classifyCounts(
            3,5,1,100))
        assertEquals(T050BadgeTone.GREEN,T050BadgePixelTone.classifyCounts(
            20,1,0,100))
    }

    @Test fun single300msTransientIsMissedRatherThanInventedAsAConfirmedEvent() {
        val m=T050TransientBadgePulseTracker()
        m.observe(frame(1,1000,emptyList()),1050)
        assertTrue(m.observe(frame(2,1330,listOf(
            badge(1,T050BadgeTone.GREEN))),1400).isEmpty())
        assertTrue(m.observe(frame(3,1660,emptyList()),1700).isEmpty())
    }
}
