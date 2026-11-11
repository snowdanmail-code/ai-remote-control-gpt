package com.example.airemote

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CanonicalPokerModelTest {
    private fun observation(count: Int, namespace: String = "fixture.alpha/site-one") =
        CanonicalPokerObservation(
            source = PokerSourceIdentity(namespace, "t1", "session", "hand"),
            observationId = "e-$count", tableCapacity = count, buttonSeat = count - 1, seatingComplete = true,
            heroSeat = 0, street = PokerStreet.FLOP,
            seats = (0 until count).map { i -> CanonicalPokerSeat(
                i, "verified-id-$i", "SameName", PokerEvidence.SOURCE_VERIFIED,
                dealtIn = true, activeInHand = i % 2 == 0, stackChips = i.toLong() * 100) },
            boardCards = listOf(CanonicalPokerCard("A", "H"), CanonicalPokerCard("K", "D"),
                CanonicalPokerCard("2", "S")), potChips = 60,
            actions = listOf(CanonicalPokerAction(0, PokerStreet.PREFLOP, 0, PokerActionKind.CALL, 10,
                PokerEvidence.OCR_ONLY), CanonicalPokerAction(1, PokerStreet.FLOP, 1,
                PokerActionKind.CHECK, null, PokerEvidence.SOURCE_VERIFIED)),
            observationEvidence = PokerEvidence.OCR_ONLY
        )
    @Test fun allTableSizesTwoThroughNineRoundTrip() {
        for (n in 2..9) {
            val v = observation(n)
            assertEquals(n, v.positions().size)
            assertEquals(n, CanonicalPokerJson.decodeObservation(CanonicalPokerJson.encodeObservation(v)).tableCapacity)
            assertEquals(v, CanonicalPokerJson.decodeObservation(CanonicalPokerJson.encodeObservation(v)))
        }
    }
    @Test fun positionsForHeadsUpFiveMaxAndSixMax() {
        assertEquals(PokerSeatPosition.BUTTON_SMALL_BLIND, observation(2).positions()[1])
        assertEquals(PokerSeatPosition.BIG_BLIND, observation(2).positions()[0])
        assertEquals(PokerSeatPosition.CUTOFF, observation(5).positions()[3])
        assertEquals(PokerSeatPosition.HIJACK, observation(6).positions()[3])
        assertEquals(PokerSeatPosition.CUTOFF, observation(6).positions()[4])
    }
    @Test fun buttonWrapAndFoldedPlayerDoNotChangeDealtPosition() {
        val v = observation(6).copy(buttonSeat = 3, seats = observation(6).seats.map {
            it.copy(activeInHand = false)
        })
        assertEquals(PokerSeatPosition.BUTTON, v.positions()[3])
        assertEquals(PokerSeatPosition.SMALL_BLIND, v.positions()[4])
        assertEquals(PokerSeatPosition.BIG_BLIND, v.positions()[5])
        assertEquals(0, v.knownActiveCount())
    }
    @Test fun missingFactsAreNeverInvented() {
        val v = observation(6).copy(buttonSeat = null, potChips = null, boardCards = null,
            seats = observation(6).seats.map { it.copy(activeInHand = null, dealtIn = null,
                stackChips = null, holeCards = null) })
        val parsed = CanonicalPokerJson.decodeObservation(CanonicalPokerJson.encodeObservation(v))
        assertNull(parsed.potChips)
        assertNull(parsed.boardCards)
        assertNull(parsed.knownActiveCount())
        assertTrue(parsed.positions().isEmpty())
        assertNull(parsed.seats[0].activeInHand)
    }
    @Test fun partialSeatManifestCannotInferPositionsOrActiveCount() {
        val partial = observation(6).copy(seatingComplete = false)
        assertNull(partial.knownActiveCount())
        assertTrue(partial.positions().isEmpty())
        assertFalse(CanonicalPokerJson.decodeObservation(CanonicalPokerJson.encodeObservation(partial)).seatingComplete)
    }
    @Test fun sameNicknameNeverMergesPlayersAcrossSources() {
        val a = observation(5)
        val b = observation(5, "fixture.beta/site-two")
        assertEquals(a.seats[0].nickname, b.seats[0].nickname)
        assertNotEquals(a.stableParticipantKey(0), b.stableParticipantKey(0))
        assertNotEquals(a.stableParticipantKey(0), a.stableParticipantKey(1))
        assertNull(a.copy(seats = a.seats.map { it.copy(identityEvidence = PokerEvidence.OCR_ONLY) })
            .stableParticipantKey(0))
    }
    @Test fun sessionScopeIsDistinctFromAnotherSession() {
        val a = observation(2)
        val b = a.copy(source = a.source.copy(sessionId = "another-session"))
        assertNotEquals(a.stableParticipantKey(0), b.stableParticipantKey(0))
    }
    @Test fun invalidTableSizesAndDuplicateSeatsRejected() {
        for (n in listOf(0, 1, 10, 15)) {
            try { observation(2).copy(tableCapacity = n); fail("Accepted $n") }
            catch (_: IllegalArgumentException) { }
        }
        try { observation(3).copy(seats = listOf(observation(3).seats[0], observation(3).seats[0]))
            fail("duplicate seat") } catch (_: IllegalArgumentException) { }
    }
    @Test fun duplicateCardsAndUnorderedActionsRejected() {
        try { observation(2).copy(boardCards = listOf(CanonicalPokerCard("A", "H"),
            CanonicalPokerCard("A", "H"))); fail("duplicate card") }
        catch (_: IllegalArgumentException) { }
        try { observation(2).copy(actions = observation(2).actions.reversed()); fail("unordered action") }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun minorVersionAllowsUnknownFutureKeysButMajorFailsClosed() {
        val raw = JSONObject(CanonicalPokerJson.encodeObservation(observation(5)))
            .put("schemaMinor", 12).put("newFutureField", JSONObject().put("ignored", true))
        val parsed = CanonicalPokerJson.decodeObservation(raw.toString())
        assertEquals(12, parsed.schemaMinor)
        assertEquals(observation(5).seats, parsed.seats)
        raw.put("schemaMajor", 2)
        try { CanonicalPokerJson.decodeObservation(raw.toString()); fail("unknown major") }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun unknownEnumsAreNeverPromotedToVerified() {
        val raw = JSONObject(CanonicalPokerJson.encodeObservation(observation(2)))
        raw.put("observationEvidence", "MAGICAL_VERIFIED")
        val first = raw.getJSONArray("seats").getJSONObject(0)
        first.put("identityEvidence", "TRUST_ME")
        assertEquals(PokerEvidence.UNKNOWN, CanonicalPokerJson.decodeObservation(raw.toString()).observationEvidence)
        assertNull(CanonicalPokerJson.decodeObservation(raw.toString()).stableParticipantKey(0))
    }
    @Test fun verifiedHandRequiresIndependentProofNotOcr() {
        val base = VerifiedPokerHandRecord(observation(2), "source-proof", "identity-proof", "terminal-proof",
            PokerEvidence.INDEPENDENT, PokerEvidence.INDEPENDENT, PokerEvidence.INDEPENDENT)
        val roundTrip = CanonicalPokerJson.decodeVerifiedHand(CanonicalPokerJson.encodeVerifiedHand(base))
        assertEquals(base, roundTrip)
        assertNull(roundTrip.heroNetChips)
        try { base.copy(terminalEvidence = PokerEvidence.OCR_ONLY); fail("OCR finalized hand") }
        catch (_: IllegalArgumentException) { }
        try { base.copy(sourceEvidence = PokerEvidence.SOURCE_VERIFIED); fail("untrusted proof") }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun verifiedHandAllowsEarlyFoldAndUnknownResult() {
        val base = VerifiedPokerHandRecord(observation(2).copy(street = PokerStreet.PREFLOP),
            "source-proof", "identity-proof", "terminal-proof",
            PokerEvidence.INDEPENDENT, PokerEvidence.INDEPENDENT, PokerEvidence.INDEPENDENT)
        assertEquals(PokerStreet.PREFLOP, base.terminalObservation.street)
        assertNull(base.heroNetChips)
    }
    @Test fun unknownFutureVerifiedFieldIgnoredAndFutureMajorRejected() {
        val r = VerifiedPokerHandRecord(observation(2), "s", "h", "t",
            PokerEvidence.INDEPENDENT, PokerEvidence.INDEPENDENT, PokerEvidence.INDEPENDENT)
        val encoded = JSONObject(CanonicalPokerJson.encodeVerifiedHand(r))
            .put("schemaMinor", 3).put("futureSettlementField", "ignored")
        assertEquals(3, CanonicalPokerJson.decodeVerifiedHand(encoded.toString()).schemaMinor)
        encoded.put("schemaMajor", 3)
        try { CanonicalPokerJson.decodeVerifiedHand(encoded.toString()); fail("future major") }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun duplicateSourceVerifiedParticipantIdentifiersRejected() {
        val original = observation(3)
        try { original.copy(seats = original.seats.mapIndexed { i, seat ->
            if (i == 1) seat.copy(participantId = original.seats[0].participantId) else seat
        }); fail("duplicate IDs") } catch (_: IllegalArgumentException) { }
    }
    @Test fun unknownActionKindAndAmountSurviveSerialization() {
        val v = observation(5).copy(actions = listOf(CanonicalPokerAction(0, PokerStreet.UNKNOWN,
            actorSeat = null, amountChips = null)))
        val encoded = CanonicalPokerJson.encodeObservation(v)
        val parsed = CanonicalPokerJson.decodeObservation(encoded)
        assertEquals(PokerActionKind.UNKNOWN, parsed.actions.first().kind)
        assertNull(parsed.actions.first().actorSeat)
        assertNull(parsed.actions.first().amountChips)
    }
}
