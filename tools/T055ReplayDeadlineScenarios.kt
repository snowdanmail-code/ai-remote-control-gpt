package com.example.airemote

/** T055 offline timing/guard fixtures, no network or real 1win data. */
object T055ReplayDeadlineScenarios {
    @JvmStatic fun main(args: Array<String>) {
        var passed = 0
        fun checkCase(name: String, ok: Boolean) {
            check(ok) { name }
            println("PASS $name")
            passed++
        }
        val normal = T055ReplayDeadlineAudit.assess(
            T055ReplayTimeline(1000, 1100, 1300, 7000))
        checkCase("timely synthetic cloud response", normal.eligibleForContractReview)
        checkCase("reports cloud latency 200ms", normal.cloudElapsedMs == 200L)
        checkCase("reports age of frame on response", normal.responseFrameAgeMs == 300L)
        checkCase("no gesture authority", normal.explains.contains("no action authority"))
        checkCase("stale at prompt", T055ReplayDeadlineAudit.assess(
            T055ReplayTimeline(1000, 2001, 2100, 7000)).verdict ==
            T055ReplayTimingVerdict.STALE_AT_PROMPT)
        checkCase("model response after 1s is stale", T055ReplayDeadlineAudit.assess(
            T055ReplayTimeline(1000, 1100, 2001, 7000)).verdict ==
            T055ReplayTimingVerdict.STALE_ON_RESPONSE)
        checkCase("at 1000ms boundary is not beyond contract limit",
            T055ReplayDeadlineAudit.assess(
                T055ReplayTimeline(1000, 1100, 2000, 7000)).eligibleForContractReview)
        checkCase("must keep timer reserve after response", T055ReplayDeadlineAudit.assess(
            T055ReplayTimeline(1000, 1050, 1100, 1720)).verdict ==
            T055ReplayTimingVerdict.INSUFFICIENT_TURN_RESERVE)
        checkCase("unknown timer cannot be treated as permission",
            T055ReplayDeadlineAudit.assess(
                T055ReplayTimeline(1000, 1050, 1100, null)).verdict ==
                T055ReplayTimingVerdict.TIMER_ALREADY_EXPIRED)
        checkCase("invalid decreasing timestamps fail closed",
            T055ReplayDeadlineAudit.assess(
                T055ReplayTimeline(1000, 900, 1200, 6000)).verdict ==
                T055ReplayTimingVerdict.INVALID_TIMESTAMPS)
        checkCase("invalid negative timer fails closed",
            T055ReplayDeadlineAudit.assess(
                T055ReplayTimeline(1000, 1100, 1200, -1)).verdict ==
                T055ReplayTimingVerdict.INVALID_TIMESTAMPS)
        checkCase("latency report differs from strategic EV metric",
            normal.verdict == T055ReplayTimingVerdict.ELIGIBLE_FOR_CONTRACT_REVIEW)
        println("Passed $passed T055 replay deadline scenarios")
    }
}
