package com.example.airemote

/** Ruleset IDs are semantic dataset partitions, not display labels.
 * Wire form: holdem.<deck>.<betting>.v<positive-integer>. Unknown IDs can be
 * observed but are NEVER approved for a verified outcome or training import. */
object PokerRulesets {
    const val STANDARD52_NO_LIMIT_V1 = "holdem.standard52.no-limit.v1"
    const val STANDARD52_POT_LIMIT_V1 = "holdem.standard52.pot-limit.v1"
    const val STANDARD52_FIXED_LIMIT_V1 = "holdem.standard52.fixed-limit.v1"
    private val pattern = Regex("^holdem\\.[a-z][a-z0-9-]*\\.[a-z][a-z0-9-]*\\.v[1-9][0-9]*$")
    val supportedForVerification: Set<String> = setOf(
        STANDARD52_NO_LIMIT_V1, STANDARD52_POT_LIMIT_V1, STANDARD52_FIXED_LIMIT_V1
    )
    fun validId(id: String): Boolean = id.length <= 128 && pattern.matches(id)
}

object PokerVerificationPolicies {
    /** The external verifier must authenticate the entire canonical record. */
    const val COMPLETE_RECORD_V1 = "independent-full-record.v1"
}

/** Deterministic, source-scoped, UTF-8 byte-length-prefixed IDs.
 * No timestamp, screen frame, nickname, or object hash enters an identifier. */
object PokerCanonicalIdentity {
    private fun key(kind: String, vararg parts: String?): String? {
        if (parts.any { it.isNullOrBlank() }) return null
        return "$kind:" + parts.joinToString("|") {
            val value = requireNotNull(it)
            "${value.toByteArray(Charsets.UTF_8).size}:$value"
        }
    }
    fun observation(source: PokerSourceIdentity, observationId: String): String? =
        key("observation", source.namespace, source.sessionId, source.tableId, observationId)
    fun event(source: PokerSourceIdentity, eventId: String): String? =
        key("event", source.namespace, source.sessionId, source.tableId, source.handId, eventId)
    fun hand(source: PokerSourceIdentity): String? =
        key("hand", source.namespace, source.sessionId, source.tableId, source.handId)
}

/** Only a trusted host-supplied implementation may check real external proof.
 * Even a record containing INDEPENDENT flags and nonempty proof refs is
 * UNVERIFIED until this separate callback authenticates its complete payload. */
fun interface PokerHandProofVerifier {
    fun verifyCompleteRecord(candidate: VerifiedPokerHandRecord): Boolean
}

enum class PokerHandVerificationStatus {
    VERIFIED, MISSING_VERIFIER, UNSUPPORTED_RULESET, UNSUPPORTED_POLICY,
    UNSUPPORTED_SCHEMA, PROOF_REJECTED
}

object PokerHandVerificationGate {
    fun verify(record: VerifiedPokerHandRecord,
               verifier: PokerHandProofVerifier?): PokerHandVerificationStatus {
        if (record.schemaMajor != CanonicalPokerSchema.MAJOR ||
            record.schemaMinor != CanonicalPokerSchema.MINOR ||
            record.terminalObservation.schemaMajor != CanonicalPokerSchema.MAJOR ||
            record.terminalObservation.schemaMinor != CanonicalPokerSchema.MINOR)
            return PokerHandVerificationStatus.UNSUPPORTED_SCHEMA
        if (record.rulesetId !in PokerRulesets.supportedForVerification)
            return PokerHandVerificationStatus.UNSUPPORTED_RULESET
        if (record.verificationPolicyId != PokerVerificationPolicies.COMPLETE_RECORD_V1)
            return PokerHandVerificationStatus.UNSUPPORTED_POLICY
        if (verifier == null) return PokerHandVerificationStatus.MISSING_VERIFIER
        return try {
            if (verifier.verifyCompleteRecord(record)) PokerHandVerificationStatus.VERIFIED
            else PokerHandVerificationStatus.PROOF_REJECTED
        } catch (_: Exception) {
            PokerHandVerificationStatus.PROOF_REJECTED
        }
    }
}

enum class PokerImportStatus { NEW, DUPLICATE, CONFLICT, UNIDENTIFIABLE }

/** Stateless import decisions: caller provides its previously stored decoded
 * record after restart. No implicit in-memory cache, SQLite, or FTP side effect.
 * A repeated identity with modified content/ruleset is a CONFLICT, not a merge. */
object PokerCanonicalImport {
    fun observation(previous: CanonicalPokerObservation?,
                    incoming: CanonicalPokerObservation): PokerImportStatus {
        val identity = incoming.stableObservationKey() ?: return PokerImportStatus.UNIDENTIFIABLE
        if (previous == null || previous.stableObservationKey() != identity) return PokerImportStatus.NEW
        return if (previous == incoming) PokerImportStatus.DUPLICATE else PokerImportStatus.CONFLICT
    }

    fun event(previousSnapshot: CanonicalPokerObservation?,
              incomingSnapshot: CanonicalPokerObservation,
              eventId: String): PokerImportStatus {
        val incomingKey = incomingSnapshot.stableEventKey(eventId) ?: return PokerImportStatus.UNIDENTIFIABLE
        val incoming = incomingSnapshot.actions.singleOrNull { it.eventId == eventId }
            ?: return PokerImportStatus.UNIDENTIFIABLE
        if (previousSnapshot == null ||
            previousSnapshot.stableEventKey(eventId) != incomingKey) return PokerImportStatus.NEW
        val previous = previousSnapshot.actions.singleOrNull { it.eventId == eventId }
            ?: return PokerImportStatus.NEW
        return if (previous == incoming && previousSnapshot.rulesetId == incomingSnapshot.rulesetId)
            PokerImportStatus.DUPLICATE else PokerImportStatus.CONFLICT
    }

    fun hand(previous: VerifiedPokerHandRecord?,
             incoming: VerifiedPokerHandRecord): PokerImportStatus {
        if (previous == null || previous.handRecordId != incoming.handRecordId)
            return PokerImportStatus.NEW
        return if (previous == incoming) PokerImportStatus.DUPLICATE else PokerImportStatus.CONFLICT
    }
}
