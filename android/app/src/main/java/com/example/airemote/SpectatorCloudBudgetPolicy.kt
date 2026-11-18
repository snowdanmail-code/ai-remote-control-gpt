package com.example.airemote

/**
 * Policy for the user's two independent ChatGPT-plan budgets.
 *
 * Ten percent of the provider-reported short reset window AND ten percent
 * of the provider-reported weekly allowance. The short window may be five
 * hours; it must NOT be guessed to be a calendar hour.
 *
 * These are plan allowance UNITS, never raw tokens or guessed API prices.
 * This module cannot authenticate usage telemetry by itself. In particular,
 * a successful ChatGPT OAuth sign-in is NOT a quota feed.
 *
 * Until a supported integration supplies verified usage and a conservative
 * per-request upper bound in the SAME units, cloud spectator requests are
 * denied. No auto-purchases, credits, plan top-ups or API-billing fallback.
 */
data class TrustedPlanAllowance(
    val sourceVerified: Boolean,
    val observedAtMs: Long,
    val shortResetAtMs: Long,
    val weeklyResetAtMs: Long,
    val shortTotal: Long,
    val weeklyTotal: Long,
    val shortAccountUsed: Long,
    val weeklyAccountUsed: Long,
    val shortAppUsed: Long,
    val weeklyAppUsed: Long
)

enum class CloudBudgetStatus {
    ALLOWED, USAGE_UNAVAILABLE, INVALID_OR_STALE_USAGE,
    COST_UNAVAILABLE, SHORT_PERIOD_EXHAUSTED, WEEKLY_EXHAUSTED
}

object SpectatorCloudBudgetPolicy {
    const val SHORT_WINDOW_PERCENT = 10
    const val WEEKLY_PERCENT = 10

    /** No source connected: cloud observation is OFF, local analysis continues. */
    fun evaluate(
        snapshot: TrustedPlanAllowance?,
        requestMaxCostUnits: Long?,
        nowMs: Long
    ): CloudBudgetStatus {
        if (snapshot == null || !snapshot.sourceVerified)
            return CloudBudgetStatus.USAGE_UNAVAILABLE
        if (requestMaxCostUnits == null || requestMaxCostUnits <= 0)
            return CloudBudgetStatus.COST_UNAVAILABLE
        if (nowMs < snapshot.observedAtMs ||
            nowMs - snapshot.observedAtMs > 60_000L ||
            snapshot.shortResetAtMs <= nowMs ||
            snapshot.weeklyResetAtMs <= nowMs ||
            snapshot.shortTotal <= 0L || snapshot.weeklyTotal <= 0L ||
            snapshot.shortAccountUsed < 0L || snapshot.weeklyAccountUsed < 0L ||
            snapshot.shortAppUsed < 0L || snapshot.weeklyAppUsed < 0L ||
            snapshot.shortAccountUsed > snapshot.shortTotal ||
            snapshot.weeklyAccountUsed > snapshot.weeklyTotal ||
            snapshot.shortAppUsed > snapshot.shortAccountUsed ||
            snapshot.weeklyAppUsed > snapshot.weeklyAccountUsed)
            return CloudBudgetStatus.INVALID_OR_STALE_USAGE
        // Division before multiplication avoids Long overflow. Being unable to
        // represent 10% precisely errs in favor of preserving plan allowance.
        val shortCeiling = snapshot.shortTotal / 10L
        val weekCeiling = snapshot.weeklyTotal / 10L
        fun canSpend(used: Long, ceiling: Long) =
            used <= ceiling && requestMaxCostUnits <= ceiling - used
        if (!canSpend(snapshot.shortAppUsed, shortCeiling) ||
            !canSpend(snapshot.shortAccountUsed, snapshot.shortTotal))
            return CloudBudgetStatus.SHORT_PERIOD_EXHAUSTED
        if (!canSpend(snapshot.weeklyAppUsed, weekCeiling) ||
            !canSpend(snapshot.weeklyAccountUsed, snapshot.weeklyTotal))
            return CloudBudgetStatus.WEEKLY_EXHAUSTED
        return CloudBudgetStatus.ALLOWED
    }

    /** The current build does not supply verified allowance telemetry. */
    fun currentSpectatorCloudState(nowMs: Long): CloudBudgetStatus =
        evaluate(null, null, nowMs)
}
