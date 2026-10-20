package com.example.airemote

import org.junit.Assert.*
import org.junit.Test

/** JVM tests run when a real Android Gradle toolchain is available. */
class TurnVisualJvmTest {
    private fun sample(name: String): VisionRaster {
        val stream = javaClass.classLoader!!.getResourceAsStream("turn/$name.png")
            ?: error("Missing anonymized action-bar fixture $name")
        val image = JvmFixtureDecoder.read(stream)
        val pixels = IntArray(540 * 1170) { 0xff202020.toInt() }
        require(image.width >= 540 && image.height >= 70)
        for (row in 0 until 70) image.argb.copyInto(pixels,
            (1085 + row) * 540, row * image.width, row * image.width + 540)
        return VisionRaster(540, 1170, pixels)
    }
    @Test fun preActionIsNotActiveTurn() {
        assertEquals(TurnDisplayState.PREACTION,
            TurnControlsVision.read(sample("preaction_a")).state)
    }
    @Test fun colouredButtonsIndicateCandidateTurn() {
        val observed = TurnControlsVision.read(sample("active_call_raise"))
        assertEquals(TurnDisplayState.READY, observed.state)
        assertTrue(observed.roles.contains(VisibleButtonRole.RED_FOLD))
    }
    @Test fun disabledBetButtonIsNotEnabled() {
        val observed = TurnControlsVision.read(sample("active_check_bet_disabled"))
        assertEquals(TurnDisplayState.READY, observed.state)
        assertFalse(observed.roles.contains(VisibleButtonRole.GOLD_BET_OR_RAISE))
    }
    @Test fun betSizingIsSeparateFromFreshTurn() {
        assertEquals(TurnDisplayState.BET_SIZING,
            TurnControlsVision.read(sample("sizing_confirm")).state)
    }
    @Test fun twoFramesCannotAuthorizeOutsideActions() {
        val detector = TurnDisplayConsistency()
        val reading = TurnControlsVision.read(sample("active_call_raise"))
        assertNull(detector.accept(TurnVisualSignal(1_000, 1, 1, false, reading), 1_000))
        val confirmed = detector.accept(TurnVisualSignal(2_300, 1, 2, false, reading), 2_300)
        assertEquals(TurnDisplayState.READY, confirmed?.state)
        assertFalse(confirmed!!.authorizedToAct)
        assertNull(detector.current(4_100))
        detector.reset()
        assertNull(detector.current(2_300))
    }
}
