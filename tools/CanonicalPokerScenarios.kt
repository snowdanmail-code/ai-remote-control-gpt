package com.example.airemote

/** Two synthetic sources with deliberately different input shapes. No external app access. */
private data class RoundFixture(val name: String, val n: Int, val button: Int, val active: Set<Int>)
private class AlphaFixtureAdapter : PokerSourceAdapter<Map<String, String>> {
    override val sourceNamespace = "fixtures.alpha/tenant-one"
    override fun normalize(event: Map<String, String>): CanonicalPokerObservation {
        val n = event.getValue("players").toInt()
        return CanonicalPokerObservation(
            source = PokerSourceIdentity(sourceNamespace, "table-7", "session-1", "hand-2"),
            observationId = "alpha-${n}", tableCapacity = n, rulesetId = PokerRulesets.STANDARD52_NO_LIMIT_V1,
            buttonSeat = n - 1, seatingComplete = true,
            seats = (0 until n).map { seat -> CanonicalPokerSeat(seat, "p$seat", "SameNick",
                PokerEvidence.SOURCE_VERIFIED, dealtIn = true, activeInHand = seat != 0, stackChips = 120L) },
            street = PokerStreet.FLOP, potChips = null,
            actions = listOf(CanonicalPokerAction(0, PokerStreet.PREFLOP, 0, PokerActionKind.CALL, 2,
                PokerEvidence.OCR_ONLY, eventId = "alpha-action-0"))
        )
    }
}
private class BetaFixtureAdapter : PokerSourceAdapter<RoundFixture> {
    override val sourceNamespace = "fixtures.beta/tenant-two"
    override fun normalize(event: RoundFixture) = CanonicalPokerObservation(
        source = PokerSourceIdentity(sourceNamespace, "table-B", "session-Z", "hand-X"),
        observationId = event.name, tableCapacity = event.n,
        rulesetId = PokerRulesets.STANDARD52_POT_LIMIT_V1,
        buttonSeat = event.button, seatingComplete = true,
        seats = (0 until event.n).map { i -> CanonicalPokerSeat(
            i, "p$i", "SameNick", PokerEvidence.SOURCE_VERIFIED,
            dealtIn = true, activeInHand = i in event.active, stackChips = null) },
        street = PokerStreet.TURN, boardCards = null, potChips = 100L
    )
}

object CanonicalPokerScenarios {
    @JvmStatic fun main(args: Array<String>) {
        var passed = 0
        fun checkThat(ok: Boolean, note: String) { check(ok) { note }; passed++ }
        val a = AlphaFixtureAdapter()
        val b = BetaFixtureAdapter()
        for (n in 2..9) {
            val first = PokerSourceAdapterBoundary.normalize(a, mapOf("players" to "$n"))
            val second = PokerSourceAdapterBoundary.normalize(b, RoundFixture("beta-$n", n, 0, setOf(0, 1)))
            checkThat(first.tableCapacity == n && first.seats.size == n, "alpha size $n")
            checkThat(second.tableCapacity == n && second.seats.size == n, "beta size $n")
            checkThat(first.positions().size == n && second.positions().size == n, "positions $n")
            checkThat(first.positions().getValue(n - 1) ==
                (if (n == 2) PokerSeatPosition.BUTTON_SMALL_BLIND else PokerSeatPosition.BUTTON), "button $n")
            checkThat(first.knownActiveCount() == n - 1 && second.knownActiveCount() == 2, "active $n")
            checkThat(first.stableParticipantKey(1) != second.stableParticipantKey(1), "namespace $n")
            checkThat(first.rulesetId != second.rulesetId, "separate rulesets $n")
            checkThat(PokerCanonicalImport.observation(first, first) == PokerImportStatus.DUPLICATE,
                "repeated observation $n")
            checkThat(PokerCanonicalImport.event(first, first, "alpha-action-0") ==
                PokerImportStatus.DUPLICATE, "repeated event $n")
            checkThat(PokerCanonicalImport.observation(first,
                first.copy(rulesetId = PokerRulesets.STANDARD52_FIXED_LIMIT_V1)) ==
                PokerImportStatus.CONFLICT, "ruleset collision $n")
        }
        val uncertain = PokerSourceAdapterBoundary.normalize(a, mapOf("players" to "6"))
            .copy(seats = PokerSourceAdapterBoundary.normalize(a, mapOf("players" to "6")).seats.map {
                it.copy(participantId = null, identityEvidence = PokerEvidence.OCR_ONLY, activeInHand = null)
            })
        checkThat(uncertain.knownActiveCount() == null && uncertain.stableParticipantKey(1) == null,
            "unknown stays unknown")
        checkThat(uncertain.positions().size == 6, "position independent of action status")
        try {
            CanonicalPokerObservation(PokerSourceIdentity("fixture"), "bad", 10)
            error("invalid capacity accepted")
        } catch (_: IllegalArgumentException) { passed++ }
        try {
            PokerSourceAdapterBoundary.normalize(object : PokerSourceAdapter<Int> {
                override val sourceNamespace = "wrong"
                override fun normalize(event: Int) = a.normalize(mapOf("players" to "2"))
            }, 1)
            error("cross-source collision accepted")
        } catch (_: IllegalArgumentException) { passed++ }
        val base = a.normalize(mapOf("players" to "2"))
        val hand = VerifiedPokerHandRecord(base, "source", "hand", "terminal",
            PokerEvidence.INDEPENDENT, PokerEvidence.INDEPENDENT, PokerEvidence.INDEPENDENT)
        checkThat(PokerHandVerificationGate.verify(hand, null) ==
            PokerHandVerificationStatus.MISSING_VERIFIER, "proof labels insufficient")
        checkThat(PokerHandVerificationGate.verify(hand, PokerHandProofVerifier { false }) ==
            PokerHandVerificationStatus.PROOF_REJECTED, "external verification denied")
        checkThat(PokerHandVerificationGate.verify(hand.copy(
            verificationPolicyId = "unsupported.v2"), PokerHandProofVerifier { true }) ==
            PokerHandVerificationStatus.UNSUPPORTED_POLICY, "policy fail closed")
        checkThat(PokerCanonicalImport.hand(hand, hand) == PokerImportStatus.DUPLICATE,
            "hand idempotence")
        println("T043 canonical synthetic JVM checks: $passed PASS (2..9 players, two adapters)")
    }
}
