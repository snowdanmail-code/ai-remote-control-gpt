package com.example.airemote

/** Pure Kotlin tests, no poker simulation and no actual Android gestures. */
fun main() {
    var count = 0
    fun test(name: String, block: () -> Unit) { block(); println("PASS $name"); count++ }
    val command = LiveCommand("req-1", "hand-1", LiveAction.CHECK, 7L, 18L,
        "state-a", 10_000L, 9_500L)
    fun valid(at: Long = 10_250L, epoch: Long = 7L, active: Boolean = true,
              root: String? = "org.example.poker", approved: String? = "org.example.poker",
              cmd: LiveCommand = command) =
        LiveDispatchFreshness.verify(cmd, at, epoch, active, root, approved)
    test("fresh frame and queue accepted") { check(valid() == LiveDispatchCheck.VALID) }
    test("no capture refused") { check(valid(active = false) == LiveDispatchCheck.MISSING_CAPTURE) }
    test("restarted capture refused") { check(valid(epoch = 8L) == LiveDispatchCheck.DIFFERENT_CAPTURE) }
    test("old queue refused") { check(valid(at = 10_601L) == LiveDispatchCheck.LATE_QUEUE) }
    test("queue at TTL allowed") { check(valid(at = 10_600L) == LiveDispatchCheck.VALID) }
    test("future dispatch refused") { check(valid(at = 9_999L) == LiveDispatchCheck.LATE_QUEUE) }
    test("future observation refused") { check(valid(cmd = command.copy(sourceCapturedAtMs=11_000L)) == LiveDispatchCheck.STALE_OBSERVATION) }
    test("negative observation refused") { check(valid(cmd = command.copy(sourceCapturedAtMs=-1)) == LiveDispatchCheck.STALE_OBSERVATION) }
    test("stale observation refused despite fresh command") { check(valid(cmd = command.copy(sourceCapturedAtMs=8_000L)) == LiveDispatchCheck.STALE_OBSERVATION) }
    test("frame at maximum age accepted") { check(valid(cmd = command.copy(sourceCapturedAtMs=8_650L)) == LiveDispatchCheck.VALID) }
    test("missing root denied") { check(valid(root = null) == LiveDispatchCheck.UNVERIFIED_FOREGROUND) }
    test("empty root denied") { check(valid(root = "") == LiveDispatchCheck.UNVERIFIED_FOREGROUND) }
    test("new window denied") { check(valid(root = "com.android.systemui") == LiveDispatchCheck.UNVERIFIED_FOREGROUND) }
    test("unapproved environment denied") { check(valid(approved = null) == LiveDispatchCheck.UNVERIFIED_FOREGROUND) }
    test("target identity changed denied") { check(valid(approved = "com.other.app") == LiveDispatchCheck.UNVERIFIED_FOREGROUND) }
    println("Passed $count delayed-dispatch safety scenarios")
}
