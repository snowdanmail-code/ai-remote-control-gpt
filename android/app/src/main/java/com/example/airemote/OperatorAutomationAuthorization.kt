package com.example.airemote

/**
 * Validates the evidence used to bind a single operator-approved virtual-chip
 * automation session to the recently observed external Android package.
 *
 * Permission to run automation on that platform remains the operator's
 * explicit assertion, not something that can be inferred from a currency label.
 * A valid result authorizes the GATE ONLY, never a specific game action.
 */
enum class OperatorAutomationAuthStatus {
    VALID, STOP_ACTIVE, ACCESSIBILITY_MISSING, TARGET_UNAVAILABLE,
    SELF_OR_SYSTEM_PACKAGE, STALE_OBSERVATION, OPERATOR_NOT_CONFIRMED
}

data class OperatorAutomationAuthRequest(
    val observedPackage: String?,
    val observedAtElapsedMs: Long?,
    val nowElapsedMs: Long,
    val ownPackage: String,
    val accessibilityConnected: Boolean,
    val stopped: Boolean,
    val operatorConfirmedVirtualOnly: Boolean,
    val operatorConfirmedPlatformAllowsAutomation: Boolean
)

object OperatorAutomationAuthorization {
    const val MAX_RECENT_PACKAGE_AGE_MS: Long = 20_000L
    private val packageFormat = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

    fun assess(request: OperatorAutomationAuthRequest): OperatorAutomationAuthStatus {
        if (request.stopped) return OperatorAutomationAuthStatus.STOP_ACTIVE
        if (!request.accessibilityConnected) return OperatorAutomationAuthStatus.ACCESSIBILITY_MISSING
        val target = request.observedPackage.orEmpty()
        if (!packageFormat.matches(target)) return OperatorAutomationAuthStatus.TARGET_UNAVAILABLE
        if (target == request.ownPackage || target == "com.android.systemui" ||
            target.startsWith("com.android.") || target.contains("permissioncontroller", ignoreCase = true))
            return OperatorAutomationAuthStatus.SELF_OR_SYSTEM_PACKAGE
        val seenAt = request.observedAtElapsedMs
            ?: return OperatorAutomationAuthStatus.STALE_OBSERVATION
        if (seenAt < 0 || request.nowElapsedMs < seenAt ||
            request.nowElapsedMs - seenAt > MAX_RECENT_PACKAGE_AGE_MS)
            return OperatorAutomationAuthStatus.STALE_OBSERVATION
        if (!request.operatorConfirmedVirtualOnly ||
            !request.operatorConfirmedPlatformAllowsAutomation)
            return OperatorAutomationAuthStatus.OPERATOR_NOT_CONFIRMED
        return OperatorAutomationAuthStatus.VALID
    }
}
