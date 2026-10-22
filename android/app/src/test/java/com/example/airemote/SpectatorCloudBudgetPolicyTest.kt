package com.example.airemote

import org.junit.Assert.assertEquals
import org.junit.Test

class SpectatorCloudBudgetPolicyTest {
    private val now = 10_000L
    private fun usage(
        shortApp: Long = 0, weeklyApp: Long = 0,
        shortAccount: Long = shortApp, weeklyAccount: Long = weeklyApp,
        verified: Boolean = true, captured: Long = now
    ) = TrustedPlanAllowance(
        sourceVerified = verified, observedAtMs = captured,
        shortResetAtMs = now + 3600000, weeklyResetAtMs = now + 86400000,
        shortTotal = 1000, weeklyTotal = 10000,
        shortAccountUsed = shortAccount, weeklyAccountUsed = weeklyAccount,
        shortAppUsed = shortApp, weeklyAppUsed = weeklyApp
    )

    @Test fun bothLimitsAreTenPercentOfDistinctProviderWindows() {
        assertEquals(10, SpectatorCloudBudgetPolicy.SHORT_WINDOW_PERCENT)
        assertEquals(10, SpectatorCloudBudgetPolicy.WEEKLY_PERCENT)
        assertEquals(CloudBudgetStatus.ALLOWED,
            SpectatorCloudBudgetPolicy.evaluate(usage(shortApp = 80, weeklyApp = 900), 20, now))
        assertEquals(CloudBudgetStatus.SHORT_PERIOD_EXHAUSTED,
            SpectatorCloudBudgetPolicy.evaluate(usage(shortApp = 81), 20, now))
        assertEquals(CloudBudgetStatus.WEEKLY_EXHAUSTED,
            SpectatorCloudBudgetPolicy.evaluate(usage(weeklyApp = 991), 10, now))
    }

    @Test fun absentAuthOnlyOrStaleUsageFailsClosed() {
        assertEquals(CloudBudgetStatus.USAGE_UNAVAILABLE,
            SpectatorCloudBudgetPolicy.currentSpectatorCloudState(now))
        assertEquals(CloudBudgetStatus.USAGE_UNAVAILABLE,
            SpectatorCloudBudgetPolicy.evaluate(usage(verified = false), 1, now))
        assertEquals(CloudBudgetStatus.INVALID_OR_STALE_USAGE,
            SpectatorCloudBudgetPolicy.evaluate(usage(captured = now - 61_000), 1, now))
        assertEquals(CloudBudgetStatus.COST_UNAVAILABLE,
            SpectatorCloudBudgetPolicy.evaluate(usage(), null, now))
    }

    @Test fun consumingMoreThanTotalAccountAllowanceAlsoBlocked() {
        assertEquals(CloudBudgetStatus.SHORT_PERIOD_EXHAUSTED,
            SpectatorCloudBudgetPolicy.evaluate(usage(shortAccount = 999), 2, now))
        assertEquals(CloudBudgetStatus.WEEKLY_EXHAUSTED,
            SpectatorCloudBudgetPolicy.evaluate(usage(weeklyAccount = 9999), 2, now))
    }

    @Test fun neverPermitNegativeUnknownOrDuplicateQuotaData() {
        assertEquals(CloudBudgetStatus.INVALID_OR_STALE_USAGE,
            SpectatorCloudBudgetPolicy.evaluate(usage(shortApp = 25, shortAccount = 0), 1, now))
        assertEquals(CloudBudgetStatus.COST_UNAVAILABLE,
            SpectatorCloudBudgetPolicy.evaluate(usage(), 0, now))
    }
}
