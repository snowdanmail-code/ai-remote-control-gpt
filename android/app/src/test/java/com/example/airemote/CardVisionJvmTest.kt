package com.example.airemote

import org.junit.Assert.*
import org.junit.Test

/** Runs with Gradle :app:testDebugUnitTest on a normal JDK; no Android UI needed. */
class CardVisionJvmTest {
    private fun sample(name: String): VisionRaster {
        val stream = javaClass.classLoader!!.getResourceAsStream(name)
            ?: error("Missing redacted card-only fixture: $name")
        return JvmFixtureDecoder.read(stream)
    }

    @Test fun preflopReadsTwoRanksAndSuits() {
        val cards = CardVisionCore.readCards(sample("cards_110_redacted.png"))
        assertTrue(cards.reason, cards.complete)
        assertEquals(listOf("Qh", "5d"), cards.hole)
        assertTrue(cards.community.isEmpty())
    }

    @Test fun flopReadsFiveDistinctCards() {
        val cards = CardVisionCore.readCards(sample("cards_180_redacted.png"))
        assertTrue(cards.reason, cards.complete)
        assertEquals(listOf("Jc", "Kh"), cards.hole)
        assertEquals(listOf("3s", "Th", "4s"), cards.community)
    }

    @Test fun riverReadsFiveCommunityCards() {
        val cards = CardVisionCore.readCards(sample("board_25_redacted.png"))
        assertTrue(cards.reason, cards.complete)
        assertEquals(listOf("7s", "As"), cards.hole)
        assertEquals(listOf("2h", "Js", "4c", "5s", "Kd"), cards.community)
    }

    @Test fun invalidHandRefused() {
        assertFalse(CardVisionCore.checkUnique(listOf("Ah", "Ah"), listOf("3s", "Th", "4s")))
        assertNull(CardVisionCore.parseRank("QQ"))
        assertNull(CardVisionCore.parseRank("K♣"))
    }

    @Test fun twoFreshFramesRequired() {
        val detector = CardReadingConsistency()
        val reading = CardReading(listOf("Jc", "Kh"), listOf("3s", "Th", "4s"),
            true, 0.96, "test")
        assertNull(detector.accept(CardVisionSignal(capturedAtMs = 1000, captureEpoch = 1, frameSequence = 1, reading = reading), 1050))
        assertNotNull(detector.accept(CardVisionSignal(capturedAtMs = 2100, captureEpoch = 1, frameSequence = 2, reading = reading), 2150))
        assertNull(detector.current(3400))
        detector.reset()
        assertNull(detector.current(2150))
    }
}
