package com.example.airemote

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CanonicalPokerModelTest {
    private fun observation(count: Int, namespace: String = "fixture.alpha/site-one") =
        CanonicalPokerObservation(
            source = PokerSourceIdentity(namespace, "t1", "session", "hand"),
            observationId = "e-$count", tableCapacity = count,
            rulesetId = PokerRulesets.STANDARD52_NO_LIMIT_V1,
            buttonSeat = count - 1, seatingComplete = true,
            heroSeat = 0, street = PokerStreet.FLOP,
            seats = (0 until count).map { i -> CanonicalPokerSeat(
                i, "verified-id-$i", "SameName", PokerEvidence.SOURCE_VERIFIED,
                dealtIn = true, activeInHand = i % 2 == 0, stackChips = i.toLong() * 100) },
            boardCards = listOf(CanonicalPokerCard("A", "H"), CanonicalPokerCard("K", "D"),
                CanonicalPokerCard("2", "S")), potChips = 60,
            actions = listOf(CanonicalPokerAction(0, PokerStreet.PREFLOP, 0, PokerActionKind.CALL, 10,
                PokerEvidence.OCR_ONLY, eventId = "action-0"), CanonicalPokerAction(1, PokerStreet.FLOP, 1,
                PokerActionKind.CHECK, null, PokerEvidence.SOURCE_VERIFIED, eventId = "action-1")),
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
        assertNull(parsed.stableParticipantKey(0)) // future minor is display-only
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
        try { base.copy(heroNetChips = 150L, resultEvidence = PokerEvidence.OCR_ONLY);
            fail("OCR-only payout was accepted") }
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
        val future = CanonicalPokerJson.decodeVerifiedHand(encoded.toString())
        assertEquals(3, future.schemaMinor)
        assertEquals(PokerHandVerificationStatus.UNSUPPORTED_SCHEMA,
            PokerHandVerificationGate.verify(future, PokerHandProofVerifier { true }))
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
            actorSeat = null, amountChips = null, eventId = "unknown-action")))
        val encoded = CanonicalPokerJson.encodeObservation(v)
        val parsed = CanonicalPokerJson.decodeObservation(encoded)
        assertEquals(PokerActionKind.UNKNOWN, parsed.actions.first().kind)
        assertNull(parsed.actions.first().actorSeat)
        assertNull(parsed.actions.first().amountChips)
    }

    private fun completedHand(o: CanonicalPokerObservation = observation(5)) =
        VerifiedPokerHandRecord(o, "src-proof", "hand-proof", "terminal-proof",
            PokerEvidence.INDEPENDENT, PokerEvidence.INDEPENDENT, PokerEvidence.INDEPENDENT)

    @Test fun rulesetsAreMandatorySyntacticallyScopedAndPartitioned() {
        val nl = observation(6)
        val pl = nl.copy(rulesetId = PokerRulesets.STANDARD52_POT_LIMIT_V1)
        val fixed = nl.copy(rulesetId = PokerRulesets.STANDARD52_FIXED_LIMIT_V1)
        assertEquals(PokerRulesets.STANDARD52_POT_LIMIT_V1,
            CanonicalPokerJson.decodeObservation(CanonicalPokerJson.encodeObservation(pl)).rulesetId)
        assertEquals(PokerRulesets.STANDARD52_FIXED_LIMIT_V1,
            CanonicalPokerJson.decodeObservation(CanonicalPokerJson.encodeObservation(fixed)).rulesetId)
        assertNotEquals(nl.stableParticipantKey(0), pl.stableParticipantKey(0))
        assertNotEquals(pl.stableParticipantKey(0), fixed.stableParticipantKey(0))
        for (invalid in listOf("", "no-limit", "holdem.standard52.no-limit.v0",
                "holdem.standard52.no-limit.v1;drop", "holdem.standard52.no-limit.v1 ")) {
            try { nl.copy(rulesetId = invalid); fail("Accepted invalid ID: $invalid") }
            catch (_: IllegalArgumentException) { }
        }
        val missing = JSONObject(CanonicalPokerJson.encodeObservation(nl))
        missing.remove("rulesetId")
        try { CanonicalPokerJson.decodeObservation(missing.toString()); fail("Missing ruleset") }
        catch (_: Exception) { }
    }

    @Test fun observationEventAndHandHaveDifferentStableIdentities() {
        val v = observation(6)
        assertNotEquals(v.stableObservationKey(), v.stableEventKey("action-0"))
        assertNotEquals(v.stableObservationKey(), v.stableHandKey())
        assertNotEquals(v.stableHandKey(), v.stableEventKey("action-0"))
        assertEquals(v.stableHandKey(), v.copy(observationId = "later").stableHandKey())
        assertEquals(v.stableEventKey("action-0"),
            v.copy(observationId = "later", capturedAtMs = 1000).stableEventKey("action-0"))
        assertNotEquals(v.stableObservationKey(), v.copy(observationId = "later").stableObservationKey())
        assertNotEquals(v.stableHandKey(),
            v.copy(source = v.source.copy(sessionId = "another")).stableHandKey())
    }

    @Test fun repeatedObservationImportAfterRestartIsIdempotent() {
        val previous = CanonicalPokerJson.decodeObservation(CanonicalPokerJson.encodeObservation(observation(5)))
        val incoming = CanonicalPokerJson.decodeObservation(CanonicalPokerJson.encodeObservation(observation(5)))
        assertEquals(PokerImportStatus.DUPLICATE, PokerCanonicalImport.observation(previous, incoming))
        assertEquals(PokerImportStatus.CONFLICT, PokerCanonicalImport.observation(previous,
            incoming.copy(potChips = 999)))
        assertEquals(PokerImportStatus.NEW, PokerCanonicalImport.observation(previous,
            incoming.copy(observationId = "new-snapshot")))
        assertEquals(PokerImportStatus.UNIDENTIFIABLE, PokerCanonicalImport.observation(null,
            incoming.copy(source = incoming.source.copy(sessionId = null))))
    }

    @Test fun eventIdsRemainStableAcrossSnapshotsAndRepeatedImport() {
        val old = observation(6)
        val replay = CanonicalPokerJson.decodeObservation(CanonicalPokerJson.encodeObservation(
            old.copy(observationId = "next-snapshot", capturedAtMs = 333)))
        assertEquals(old.actions.map { it.eventId }, replay.actions.map { it.eventId })
        assertEquals(PokerImportStatus.DUPLICATE, PokerCanonicalImport.event(old, replay, "action-0"))
        assertEquals(PokerImportStatus.NEW, PokerCanonicalImport.event(old, replay,
            "no-such-event").let { if (it == PokerImportStatus.UNIDENTIFIABLE) PokerImportStatus.NEW else it })
        assertEquals(PokerImportStatus.CONFLICT, PokerCanonicalImport.event(old,
            replay.copy(actions = replay.actions.map {
                if (it.eventId == "action-0") it.copy(amountChips = 444) else it
            }), "action-0"))
        assertEquals(PokerImportStatus.NEW, PokerCanonicalImport.event(old,
            replay.copy(source = replay.source.copy(handId = "next-hand")), "action-0"))
    }

    @Test fun eventDuplicateIdentifiersAndTamperingAreRejected() {
        val a = observation(3)
        try {
            a.copy(actions = a.actions.mapIndexed { i, act ->
                if (i == 1) act.copy(eventId = "action-0") else act
            })
            fail("Duplicate eventId")
        } catch (_: IllegalArgumentException) { }
        val json = JSONObject(CanonicalPokerJson.encodeObservation(a))
        json.getJSONArray("actions").getJSONObject(1).put("eventId", "action-0")
        try { CanonicalPokerJson.decodeObservation(json.toString()); fail("Duplicated IDs on wire") }
        catch (_: IllegalArgumentException) { }
        json.getJSONArray("actions").getJSONObject(1).remove("eventId")
        try { CanonicalPokerJson.decodeObservation(json.toString()); fail("No event ID") }
        catch (_: Exception) { }
    }

    @Test fun completedHandIdentitySurvivesWireAndRestartAndDetectsConflicts() {
        val existing = completedHand()
        val saved = CanonicalPokerJson.encodeVerifiedHand(existing)
        val restored = CanonicalPokerJson.decodeVerifiedHand(saved)
        val repeated = CanonicalPokerJson.decodeVerifiedHand(saved)
        assertEquals(existing.handRecordId, restored.handRecordId)
        assertEquals(existing.handRecordId, repeated.handRecordId)
        assertEquals(PokerImportStatus.DUPLICATE, PokerCanonicalImport.hand(restored, repeated))
        val tamperedRules = repeated.copy(terminalObservation = repeated.terminalObservation.copy(
            rulesetId = PokerRulesets.STANDARD52_FIXED_LIMIT_V1),
            rulesetId = PokerRulesets.STANDARD52_FIXED_LIMIT_V1)
        assertEquals(PokerImportStatus.CONFLICT, PokerCanonicalImport.hand(restored, tamperedRules))
        assertEquals(PokerImportStatus.CONFLICT, PokerCanonicalImport.hand(restored,
            repeated.copy(terminalProofRef = "different-proof")))
        val nextHand = completedHand(observation(5).copy(source = observation(5).source.copy(
            handId = "another-hand")))
        assertEquals(PokerImportStatus.NEW, PokerCanonicalImport.hand(restored, nextHand))
    }

    @Test fun tamperedRulesetAndReceiptIdentifiersFailClosed() {
        val base = completedHand()
        val json = JSONObject(CanonicalPokerJson.encodeVerifiedHand(base))
        json.put("rulesetId", PokerRulesets.STANDARD52_FIXED_LIMIT_V1)
        try { CanonicalPokerJson.decodeVerifiedHand(json.toString()); fail("Mismatched ruleset") }
        catch (_: IllegalArgumentException) { }
        json.put("rulesetId", base.rulesetId)
        json.put("handRecordId", "forged-record-id")
        try { CanonicalPokerJson.decodeVerifiedHand(json.toString()); fail("Mismatched hand receipt") }
        catch (_: IllegalArgumentException) { }
        json.put("handRecordId", base.handRecordId)
        json.remove("verificationPolicyId")
        try { CanonicalPokerJson.decodeVerifiedHand(json.toString()); fail("Implicit policy accepted") }
        catch (_: Exception) { }
    }

    @Test fun independentLabelsAndProofReferencesAloneCannotVerify() {
        val claimed = completedHand()
        assertEquals(PokerHandVerificationStatus.MISSING_VERIFIER,
            PokerHandVerificationGate.verify(claimed, null))
        assertEquals(PokerHandVerificationStatus.PROOF_REJECTED,
            PokerHandVerificationGate.verify(claimed, PokerHandProofVerifier { false }))
        val trustedFixture = PokerHandProofVerifier { record -> record == claimed }
        assertEquals(PokerHandVerificationStatus.VERIFIED,
            PokerHandVerificationGate.verify(claimed, trustedFixture))
        assertEquals(PokerHandVerificationStatus.PROOF_REJECTED,
            PokerHandVerificationGate.verify(claimed.copy(terminalProofRef = "forged"), trustedFixture))
        assertEquals(PokerHandVerificationStatus.PROOF_REJECTED,
            PokerHandVerificationGate.verify(claimed.copy(terminalObservation =
                claimed.terminalObservation.copy(potChips = 1000000)), trustedFixture))
    }

    @Test fun unsupportedVerificationPolicyAndRulesetCannotValidate() {
        val claimed = completedHand()
        val acceptingStub = PokerHandProofVerifier { true }
        val unsupportedPolicy = claimed.copy(verificationPolicyId = "independent-full-record.v2")
        assertEquals(PokerHandVerificationStatus.UNSUPPORTED_POLICY,
            PokerHandVerificationGate.verify(unsupportedPolicy, acceptingStub))
        assertEquals(PokerHandVerificationStatus.UNSUPPORTED_POLICY,
            PokerHandVerificationGate.verify(CanonicalPokerJson.decodeVerifiedHand(
                CanonicalPokerJson.encodeVerifiedHand(unsupportedPolicy)), acceptingStub))
        val unsupportedRuleset = claimed.copy(
            terminalObservation = claimed.terminalObservation.copy(
                rulesetId = "holdem.shortdeck36.no-limit.v1"),
            rulesetId = "holdem.shortdeck36.no-limit.v1")
        assertEquals(PokerHandVerificationStatus.UNSUPPORTED_RULESET,
            PokerHandVerificationGate.verify(unsupportedRuleset, acceptingStub))
    }

    @Test fun unsupportedMinorCannotVerifyEvenWithAcceptingVerifier() {
        val claimed = completedHand()
        val acceptingStub = PokerHandProofVerifier { true }
        val future = claimed.copy(schemaMinor = CanonicalPokerSchema.MINOR + 1)
        assertEquals(PokerHandVerificationStatus.UNSUPPORTED_SCHEMA,
            PokerHandVerificationGate.verify(future, acceptingStub))
        val futureNested = claimed.copy(terminalObservation = claimed.terminalObservation.copy(
            schemaMinor = CanonicalPokerSchema.MINOR + 1))
        assertEquals(PokerHandVerificationStatus.UNSUPPORTED_SCHEMA,
            PokerHandVerificationGate.verify(futureNested, acceptingStub))
    }

    @Test fun rulesetUnknownStillSerializableButNeverVerified() {
        val observation = observation(2).copy(rulesetId = "holdem.futurevariant.no-limit.v1")
        assertEquals(observation, CanonicalPokerJson.decodeObservation(
            CanonicalPokerJson.encodeObservation(observation)))
        assertEquals(PokerHandVerificationStatus.UNSUPPORTED_RULESET,
            PokerHandVerificationGate.verify(completedHand(observation),
                PokerHandProofVerifier { true }))
    }
}
