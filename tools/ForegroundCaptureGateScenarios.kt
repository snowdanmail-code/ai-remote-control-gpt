import com.example.airemote.ForegroundCaptureGate

fun main() {
    var assertions = 0
    fun checkCase(ok: Boolean, message: String) {
        check(ok) { message }
        assertions++
    }
    val gate = ForegroundCaptureGate()
    checkCase(gate.acquireTicket() == null, "Starts closed")
    checkCase(!gate.canPublish(null), "Null ticket is never valid")
    gate.onActivityHidden()
    val first = gate.acquireTicket()
    checkCase(first != null, "Background permits read-only capture")
    checkCase(gate.canPublish(first), "Same background epoch permits publication")
    gate.onActivityVisible()
    checkCase(gate.acquireTicket() == null, "Foreground forbids new tickets")
    checkCase(!gate.canPublish(first), "Foreground cancels in-flight capture")
    gate.onActivityHidden()
    val second = gate.acquireTicket()
    checkCase(second != first, "New background epoch differs")
    checkCase(!gate.canPublish(first), "Old ticket remains invalid")
    checkCase(gate.canPublish(second), "New ticket is valid")
    gate.onActivityHidden()
    checkCase(!gate.canPublish(second), "Another transition invalidates old ticket")
    gate.onActivityVisible()
    gate.onActivityVisible()
    checkCase(gate.acquireTicket() == null, "Repeated visibility stays closed")
    println("PASS: $assertions Kotlin visibility-gate assertions")
}
