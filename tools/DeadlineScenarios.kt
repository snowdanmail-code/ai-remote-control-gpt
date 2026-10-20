package com.example.airemote
object DeadlineScenarios {
    @JvmStatic fun main(args: Array<String>) {
        var checks = 0
        fun yes(actual: DeadlineResult, target: DeadlineResult) { check(actual == target); checks++ }
        fun test(c:Long,o:Long,n:Long,r:Long?,reserve:Long=1000,age:Long=2000) =
            DecisionDeadlineGate.assess(c,o,n,r,reserve,age)
        yes(test(1000,1000,1001,5000), DeadlineResult.SAFE_TO_CONTINUE)
        yes(test(1000,1500,1800,1700), DeadlineResult.INSUFFICIENT_TIME)
        yes(test(1000,1600,2000,2500), DeadlineResult.SAFE_TO_CONTINUE)
        yes(test(1000,1600,2000,1999), DeadlineResult.INSUFFICIENT_TIME)
        yes(test(1000,1600,4000,5000), DeadlineResult.FRAME_STALE)
        yes(test(1000,1001,1001,null), DeadlineResult.TIMER_UNKNOWN)
        yes(test(1000,1001,1001,-1), DeadlineResult.TIMER_UNKNOWN)
        yes(test(1200,1100,1300,5000), DeadlineResult.CLOCK_INVALID)
        yes(test(1200,1300,1299,5000), DeadlineResult.CLOCK_INVALID)
        yes(test(-1,1000,1200,5000), DeadlineResult.CLOCK_INVALID)
        yes(test(1000,1000,1000,1000), DeadlineResult.SAFE_TO_CONTINUE)
        yes(test(1000,1000,1001,1000), DeadlineResult.INSUFFICIENT_TIME)
        println("DecisionDeadlineGate: $checks scenarios OK")
    }
}
