package com.example.airemote

/** Pure contract tests: no game API, credentials, Android gestures or real bets. */
object TrustedPlayerInputScenarios {
    @JvmStatic fun main(args: Array<String>) {
        var checked=0
        fun verify(condition: Boolean, description: String) { check(condition) { description }; checked++ }
        verify(TrustedPlayerInputPolicy.route(false,false)==TrustedPlayerInputRoute.NONE,"No provider")
        verify(TrustedPlayerInputPolicy.route(true,false)==TrustedPlayerInputRoute.SIGNED_PACKET,"Signed provider")
        verify(TrustedPlayerInputPolicy.route(false,true)==TrustedPlayerInputRoute.VERIFIED_CALLBACK,"Verified callback provider")
        verify(TrustedPlayerInputPolicy.route(true,true)==TrustedPlayerInputRoute.CONFLICT,"Two providers conflict")
        for (signed in listOf(false,true)) for (callback in listOf(false,true)) {
            val expected = signed != callback
            verify(TrustedPlayerInputPolicy.mayArm(true,true,signed,callback)==expected,"Only one provider can arm")
            verify(!TrustedPlayerInputPolicy.mayArm(false,true,signed,callback),"Missing turn proof fails closed")
            verify(!TrustedPlayerInputPolicy.mayArm(true,false,signed,callback),"Missing effect proof fails closed")
        }
        println("TrustedPlayerInputScenarios: $checked checks passed")
    }
}
