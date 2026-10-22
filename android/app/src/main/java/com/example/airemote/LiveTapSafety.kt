package com.example.airemote

/** A tap must be tied to an independently recognized, small, active button. */
data class VerifiedTapArea(
    val left: Int, val top: Int, val right: Int, val bottom: Int,
    val centerX: Int, val centerY: Int,
    val screenWidth: Int, val screenHeight: Int,
    val exactAction: LiveAction,
    val recognizedLabel: String,
    val foregroundPackage: String,
    /** Independently sampled Android root-window generation. */
    val windowGeneration: Long = -1L
)

enum class TapSafetyResult {
    VALID, WRONG_PACKAGE, WRONG_ACTION, INVALID_GEOMETRY, SYSTEM_UI_RISK,
    UNVERIFIED_LABEL, SCREEN_MISMATCH, STALE_FOREGROUND
}

/** No Android dependency: all geometry and identity checks can be unit-tested. */
object LiveTapSafety {
    const val MAX_FOREGROUND_AGE_MS = 1_000L

    fun verify(
        tap: VerifiedTapArea,
        command: LiveCommand,
        actualForegroundPackage: String?,
        foregroundSeenAtMs: Long,
        nowMs: Long,
        actualScreenWidth: Int,
        actualScreenHeight: Int,
        approvedPackage: String?
    ): TapSafetyResult {
        if (approvedPackage.isNullOrBlank() || tap.foregroundPackage != approvedPackage ||
            actualForegroundPackage != approvedPackage) return TapSafetyResult.WRONG_PACKAGE
        if (nowMs < foregroundSeenAtMs || nowMs - foregroundSeenAtMs > MAX_FOREGROUND_AGE_MS)
            return TapSafetyResult.STALE_FOREGROUND
        if (tap.exactAction != command.action) return TapSafetyResult.WRONG_ACTION
        if (!labelMatches(tap.recognizedLabel, command.action))
            return TapSafetyResult.UNVERIFIED_LABEL
        if (tap.screenWidth <= 0 || tap.screenHeight <= 0 ||
            tap.screenWidth != actualScreenWidth || tap.screenHeight != actualScreenHeight)
            return TapSafetyResult.SCREEN_MISMATCH
        if (tap.left < 0 || tap.top < 0 || tap.right > tap.screenWidth ||
            tap.bottom > tap.screenHeight || tap.right <= tap.left || tap.bottom <= tap.top ||
            tap.centerX !in tap.left..tap.right || tap.centerY !in tap.top..tap.bottom ||
            tap.right - tap.left < 25 || tap.bottom - tap.top < 25 ||
            tap.right - tap.left > tap.screenWidth / 2 ||
            tap.bottom - tap.top > tap.screenHeight / 5)
            return TapSafetyResult.INVALID_GEOMETRY
        // Never touch the Android status bar, camera cutout or navigation gestures.
        if (tap.top < (tap.screenHeight * 0.07).toInt() ||
            tap.bottom > (tap.screenHeight * 0.975).toInt() ||
            tap.left < (tap.screenWidth * 0.015).toInt() ||
            tap.right > (tap.screenWidth * 0.985).toInt())
            return TapSafetyResult.SYSTEM_UI_RISK
        return TapSafetyResult.VALID
    }

    private fun labelMatches(label: String, action: LiveAction): Boolean {
        // Only exact visible labels from the actual target; color is not evidence.
        val normalized = label.trim().uppercase().replace(Regex("\\s+"), " ")
        return when (action) {
            LiveAction.FOLD -> normalized in setOf("FOLD", "СБРОС", "ПАС")
            LiveAction.CHECK -> normalized in setOf("CHECK", "ЧЕК", "ПРОПУСТИТЬ")
            LiveAction.CALL -> normalized in setOf("CALL", "КОЛЛ", "УРАВНЯТЬ") ||
                VirtualChipDisplayParser.parseLabel(label)
                    ?.let { it.kind == NumericCandidateKind.CALL_AMOUNT } == true
            LiveAction.BET -> normalized in setOf("BET", "СТАВКА", "ПОСТАВИТЬ")
            LiveAction.RAISE -> normalized in setOf("RAISE", "РЕЙЗ", "ПОВЫСИТЬ")
        }
    }
}

/** Deliberately unconfigured until exact installed package and permission are verified. */
object LiveGamePermissionGate {
    @Volatile private var permittedExternalAutomation = false
    @Volatile private var verifiedInstalledPackage: String? = null

    @Synchronized fun grantForVerifiedEnvironment(packageName: String, platformExplicitlyPermits: Boolean) {
        val plausiblePackage = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
        permittedExternalAutomation = platformExplicitlyPermits && plausiblePackage.matches(packageName)
        verifiedInstalledPackage = if (permittedExternalAutomation) packageName else null
    }
    @Synchronized fun revoke() {
        permittedExternalAutomation = false
        verifiedInstalledPackage = null
    }
    fun permitted(): Boolean = permittedExternalAutomation
    fun packageName(): String? = verifiedInstalledPackage
}
