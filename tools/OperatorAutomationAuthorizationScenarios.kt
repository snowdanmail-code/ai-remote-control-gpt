package com.example.airemote

fun main() {
    val good = OperatorAutomationAuthRequest("com.game.client", 1000L, 2000L,
        "com.example.airemote", true, false, true, true)
    var checks = 0
    fun expect(state: OperatorAutomationAuthStatus, request: OperatorAutomationAuthRequest) {
        check(OperatorAutomationAuthorization.assess(request) == state)
        checks++
    }
    expect(OperatorAutomationAuthStatus.VALID, good)
    expect(OperatorAutomationAuthStatus.STOP_ACTIVE, good.copy(stopped = true))
    expect(OperatorAutomationAuthStatus.ACCESSIBILITY_MISSING, good.copy(accessibilityConnected = false))
    expect(OperatorAutomationAuthStatus.TARGET_UNAVAILABLE, good.copy(observedPackage = null))
    expect(OperatorAutomationAuthStatus.TARGET_UNAVAILABLE, good.copy(observedPackage = "client"))
    expect(OperatorAutomationAuthStatus.SELF_OR_SYSTEM_PACKAGE, good.copy(observedPackage = "com.example.airemote"))
    expect(OperatorAutomationAuthStatus.SELF_OR_SYSTEM_PACKAGE, good.copy(observedPackage = "com.android.systemui"))
    expect(OperatorAutomationAuthStatus.SELF_OR_SYSTEM_PACKAGE, good.copy(observedPackage = "com.android.settings"))
    expect(OperatorAutomationAuthStatus.STALE_OBSERVATION, good.copy(nowElapsedMs = 22_001L))
    expect(OperatorAutomationAuthStatus.STALE_OBSERVATION, good.copy(nowElapsedMs = 999L))
    expect(OperatorAutomationAuthStatus.STALE_OBSERVATION, good.copy(observedAtElapsedMs = null))
    expect(OperatorAutomationAuthStatus.OPERATOR_NOT_CONFIRMED, good.copy(operatorConfirmedVirtualOnly = false))
    expect(OperatorAutomationAuthStatus.OPERATOR_NOT_CONFIRMED, good.copy(operatorConfirmedPlatformAllowsAutomation = false))
    expect(OperatorAutomationAuthStatus.VALID, good.copy(nowElapsedMs = 21_000L))
    println("OperatorAutomationAuthorizationScenarios: $checks passed")
}
