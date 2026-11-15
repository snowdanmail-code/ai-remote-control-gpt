package com.example.airemote

/**
 * T055 deterministic replay of the GPT recommendation deadline.
 * No network, Android UI, source identity grants, gestures, or real bets.
 *
 * Time values are monotonic milliseconds from the SAME clock origin.
 * Expected model reasoning quality is deliberately not inferred from timing.
 */
enum class T055ReplayTimingVerdict {
    ELIGIBLE_FOR_CONTRACT_REVIEW,
    INVALID_TIMESTAMPS,
    STALE_AT_PROMPT,
    STALE_ON_RESPONSE,
    TIMER_ALREADY_EXPIRED,
    INSUFFICIENT_TURN_RESERVE
}
data class T055ReplayTimeline(
    val frameCapturedAtMs: Long,
    val cloudRequestAtMs: Long,
    val cloudResponseAtMs: Long,
    val turnTimerRemainingAtCaptureMs: Long?,
    val reviewAndHandoffReserveMs: Long = 200L
)
data class T055ReplayTimingResult(
    val verdict: T055ReplayTimingVerdict,
    val requestFrameAgeMs: Long?,
    val responseFrameAgeMs: Long?,
    val cloudElapsedMs: Long?,
    val remainingAtResponseMs: Long?,
    val explains: String
) {
    /** Passing this gate is not sufficient to authorize a model decision. */
    val eligibleForContractReview: Boolean
        get() = verdict == T055ReplayTimingVerdict.ELIGIBLE_FOR_CONTRACT_REVIEW
}

object T055ReplayDeadlineAudit {
    const val FRAME_MAX_AGE_MS = 1000L
    const val TURN_RESERVE_MS = 1600L

    fun assess(t: T055ReplayTimeline): T055ReplayTimingResult {
        fun result(v: T055ReplayTimingVerdict,
                   req: Long? = null, res: Long? = null,
                   cloud: Long? = null, remaining: Long? = null) =
            T055ReplayTimingResult(v, req, res, cloud, remaining,
                "T055_REPLAY_ONLY: ${v.name}; no action authority")
        if (t.frameCapturedAtMs < 0 || t.cloudRequestAtMs < t.frameCapturedAtMs ||
            t.cloudResponseAtMs < t.cloudRequestAtMs ||
            t.reviewAndHandoffReserveMs !in 0L..1200L ||
            (t.turnTimerRemainingAtCaptureMs != null &&
                t.turnTimerRemainingAtCaptureMs < 0L))
            return result(T055ReplayTimingVerdict.INVALID_TIMESTAMPS)
        val reqAge = t.cloudRequestAtMs - t.frameCapturedAtMs
        val resAge = t.cloudResponseAtMs - t.frameCapturedAtMs
        val elapsed = t.cloudResponseAtMs - t.cloudRequestAtMs
        val remaining = t.turnTimerRemainingAtCaptureMs?.minus(resAge)
        if (reqAge > FRAME_MAX_AGE_MS)
            return result(T055ReplayTimingVerdict.STALE_AT_PROMPT,
                reqAge, resAge, elapsed, remaining)
        if (resAge > FRAME_MAX_AGE_MS)
            return result(T055ReplayTimingVerdict.STALE_ON_RESPONSE,
                reqAge, resAge, elapsed, remaining)
        if (remaining == null || remaining <= 0L)
            return result(T055ReplayTimingVerdict.TIMER_ALREADY_EXPIRED,
                reqAge, resAge, elapsed, remaining)
        // The existing PlayerModelDecisionGuard requires >=1600 ms remaining;
        // this audit also budgets review/handoff overhead without relaxing it.
        if (remaining < TURN_RESERVE_MS + t.reviewAndHandoffReserveMs)
            return result(T055ReplayTimingVerdict.INSUFFICIENT_TURN_RESERVE,
                reqAge, resAge, elapsed, remaining)
        return result(T055ReplayTimingVerdict.ELIGIBLE_FOR_CONTRACT_REVIEW,
            reqAge, resAge, elapsed, remaining)
    }
}
