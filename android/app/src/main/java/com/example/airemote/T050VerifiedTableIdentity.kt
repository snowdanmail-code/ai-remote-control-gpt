package com.example.airemote

/**
 * T050 checkpoint 01: independent table-identity ledger.
 *
 * A window/capture epoch is NOT a table identity. This ledger learns a
 * table key only after externally authenticated game-state provenance.
 * It never turns an OCR label or screen coordinate into such a key.
 */
data class T050VerifiedTableIdentity(
    val sourcePackage: String,
    val captureEpoch: Long,
    val windowGeneration: Long,
    val tableId: String
) {
    fun valid(): Boolean =
        sourcePackage.matches(Regex("""[A-Za-z][A-Za-z0-9_.]{2,159}""")) &&
        captureEpoch >= 0L && windowGeneration >= 0L &&
        tableId.length in 1..96 && tableId.none { it.isISOControl() }
}

enum class T050TableIdentityResult {
    UNVERIFIED, FIRST_TRUSTED_ID, SAME_TRUSTED_TABLE, CHANGED_TABLE_OR_WINDOW
}

class T050TableIdentityLedger {
    private var bound: T050VerifiedTableIdentity? = null

    @Synchronized fun reset() { bound = null }

    @Synchronized fun current(): T050VerifiedTableIdentity? = bound

    @Synchronized fun accept(id: T050VerifiedTableIdentity,
                             independentSourceVerified: Boolean):
        T050TableIdentityResult {
        if (!independentSourceVerified || !id.valid())
            return T050TableIdentityResult.UNVERIFIED
        val prior = bound
        if (prior == null) {
            bound = id
            return T050TableIdentityResult.FIRST_TRUSTED_ID
        }
        if (prior == id) return T050TableIdentityResult.SAME_TRUSTED_TABLE
        // Do not install the new table's identity automatically. Existing
        // seating evidence is for a different game and must be invalidated.
        return T050TableIdentityResult.CHANGED_TABLE_OR_WINDOW
    }

    @Synchronized fun belongsTo(id: T050VerifiedTableIdentity): Boolean =
        bound != null && bound == id
}
