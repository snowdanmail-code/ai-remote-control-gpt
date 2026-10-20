package com.example.airemote

import org.junit.Assert.assertEquals
import org.junit.Test

class OperatorAutomationAuthorizationJvmTest {
    private fun request() = OperatorAutomationAuthRequest(
        "com.game.client", 10_000L, 10_500L, "com.example.airemote",
        accessibilityConnected = true, stopped = false,
        operatorConfirmedVirtualOnly = true, operatorConfirmedPlatformAllowsAutomation = true)

    @Test fun validExplicitSession() = assertEquals(
        OperatorAutomationAuthStatus.VALID, OperatorAutomationAuthorization.assess(request()))

    @Test fun stopPreventsGrant() = assertEquals(
        OperatorAutomationAuthStatus.STOP_ACTIVE,
        OperatorAutomationAuthorization.assess(request().copy(stopped = true)))

    @Test fun staleObservationPreventsGrant() = assertEquals(
        OperatorAutomationAuthStatus.STALE_OBSERVATION,
        OperatorAutomationAuthorization.assess(request().copy(nowElapsedMs = 31_000L)))

    @Test fun otherSystemPackagePreventsGrant() = assertEquals(
        OperatorAutomationAuthStatus.SELF_OR_SYSTEM_PACKAGE,
        OperatorAutomationAuthorization.assess(request().copy(observedPackage = "com.android.settings")))

    @Test fun withoutPlatformConfirmationPreventsGrant() = assertEquals(
        OperatorAutomationAuthStatus.OPERATOR_NOT_CONFIRMED,
        OperatorAutomationAuthorization.assess(request().copy(operatorConfirmedPlatformAllowsAutomation = false)))
}
