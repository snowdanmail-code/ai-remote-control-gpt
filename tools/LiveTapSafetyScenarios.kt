package com.example.airemote

/** Pure input validation tests. No game simulator and no Android gestures are sent. */
fun main() {
    var n = 0
    fun test(name: String, block: () -> Unit) { block(); println("PASS $name"); n++ }
    val c = LiveCommand("cmd-001", "hand-1", LiveAction.CALL, 3, 10, "fingerprint", 1000)
    val t = VerifiedTapArea(245, 2000, 480, 2100, 355, 2040,
        1080, 2340, LiveAction.CALL, "CALL", "com.example.verifiedpoker")
    fun checkTap(target: VerifiedTapArea = t, command: LiveCommand = c,
        actualPackage: String? = "com.example.verifiedpoker", seen: Long = 1400,
        now: Long = 1700, w: Int = 1080, h: Int = 2340,
        approved: String? = "com.example.verifiedpoker") =
        LiveTapSafety.verify(target, command, actualPackage, seen, now, w, h, approved)
    test("valid exact target") { check(checkTap() == TapSafetyResult.VALID) }
    test("no approved package") { check(checkTap(approved=null) == TapSafetyResult.WRONG_PACKAGE) }
    test("untrusted target package") { check(checkTap(approved="other.app") == TapSafetyResult.WRONG_PACKAGE) }
    test("foreground switched") { check(checkTap(actualPackage="com.other.app") == TapSafetyResult.WRONG_PACKAGE) }
    test("no foreground") { check(checkTap(actualPackage=null) == TapSafetyResult.WRONG_PACKAGE) }
    test("stale foreground") { check(checkTap(now=3000) == TapSafetyResult.STALE_FOREGROUND) }
    test("future foreground") { check(checkTap(now=1200) == TapSafetyResult.STALE_FOREGROUND) }
    test("action mismatch") { check(checkTap(target=t.copy(exactAction=LiveAction.FOLD)) == TapSafetyResult.WRONG_ACTION) }
    test("same color not same label") { check(checkTap(target=t.copy(recognizedLabel="CHECK")) == TapSafetyResult.UNVERIFIED_LABEL) }
    test("empty label") { check(checkTap(target=t.copy(recognizedLabel="")) == TapSafetyResult.UNVERIFIED_LABEL) }
    test("uncertain call amount not trusted") { check(checkTap(target=t.copy(recognizedLabel="CALL 50")) == TapSafetyResult.UNVERIFIED_LABEL) }
    test("correct case ignored") { check(checkTap(target=t.copy(recognizedLabel="call")) == TapSafetyResult.VALID) }
    test("wrong screen width") { check(checkTap(w=720) == TapSafetyResult.SCREEN_MISMATCH) }
    test("wrong screen height") { check(checkTap(h=1920) == TapSafetyResult.SCREEN_MISMATCH) }
    test("negative width") { check(checkTap(target=t.copy(screenWidth=-1)) == TapSafetyResult.SCREEN_MISMATCH) }
    test("left out of display") { check(checkTap(target=t.copy(left=-5)) == TapSafetyResult.INVALID_GEOMETRY) }
    test("right out of display") { check(checkTap(target=t.copy(right=1081)) == TapSafetyResult.INVALID_GEOMETRY) }
    test("inverted rectangle") { check(checkTap(target=t.copy(right=100)) == TapSafetyResult.INVALID_GEOMETRY) }
    test("tap center outside") { check(checkTap(target=t.copy(centerX=800)) == TapSafetyResult.INVALID_GEOMETRY) }
    test("button too narrow") { check(checkTap(target=t.copy(right=260,centerX=250)) == TapSafetyResult.INVALID_GEOMETRY) }
    test("button too wide") { check(checkTap(target=t.copy(left=1,right=1000)) == TapSafetyResult.INVALID_GEOMETRY) }
    test("status bar excluded") { check(checkTap(target=t.copy(top=90,bottom=150,centerY=120)) == TapSafetyResult.SYSTEM_UI_RISK) }
    test("navigation excluded") { check(checkTap(target=t.copy(top=2180,bottom=2310,centerY=2200)) == TapSafetyResult.SYSTEM_UI_RISK) }
    test("left edge excluded") { check(checkTap(target=t.copy(left=5,right=300,centerX=250)) == TapSafetyResult.SYSTEM_UI_RISK) }
    test("right edge excluded") { check(checkTap(target=t.copy(left=770,right=1072,centerX=950)) == TapSafetyResult.SYSTEM_UI_RISK) }
    test("approved policy initially false") { LiveGamePermissionGate.revoke(); check(!LiveGamePermissionGate.permitted()) }
    test("invalid package cannot unlock") {
        LiveGamePermissionGate.grantForVerifiedEnvironment("1win", true)
        check(!LiveGamePermissionGate.permitted())
    }
    test("no platform permission cannot unlock") {
        LiveGamePermissionGate.grantForVerifiedEnvironment("com.example.verifiedpoker", false)
        check(!LiveGamePermissionGate.permitted())
    }
    test("explicit approval accepts valid package; revoke works") {
        LiveGamePermissionGate.grantForVerifiedEnvironment("com.example.verifiedpoker", true)
        check(LiveGamePermissionGate.permitted())
        LiveGamePermissionGate.revoke()
        check(!LiveGamePermissionGate.permitted() && LiveGamePermissionGate.packageName()==null)
    }
    println("Passed $n LiveTapSafety scenarios (no live gestures)")
}
