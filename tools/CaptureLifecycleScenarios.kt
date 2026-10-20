import com.example.airemote.*

/** Only service lifecycle transitions: no Android SDK, no game, no gestures. */
object CaptureLifecycleScenarios {
    @JvmStatic fun main(args: Array<String>) {
        val g = CaptureLifecycleGuard()
        var checks = 0
        fun t(condition: Boolean, message: String) { check(condition) { message }; checks++ }
        t(g.current() == CaptureLifecycle.IDLE, "starts idle")
        t(g.mayRequestNewCapture(), "request allowed initially")
        t(g.beginStart(), "first consent accepted")
        t(!g.mayRequestNewCapture(), "no repeat request during startup")
        t(!g.beginStart(), "old projection cannot be restarted")
        t(g.markActive(), "running after initialization")
        t(g.current() == CaptureLifecycle.ACTIVE, "phase active")
        t(!g.beginStart(), "cannot start in active session")
        g.requestStop()
        t(g.current() == CaptureLifecycle.STOPPING, "stop enters closing phase")
        t(!g.beginStart(), "new start rejected during teardown")
        t(!g.markActive(), "delayed startup cannot override stop")
        g.completeStop()
        t(g.mayRequestNewCapture(), "start allowed only after teardown")
        t(g.beginStart(), "fresh token starts new service")
        g.requestStop()
        t(!g.mayRequestNewCapture(), "stopping startup blocks restart")
        g.completeStop()
        t(g.current() == CaptureLifecycle.IDLE, "failed start eventually releases gate")
        g.requestStop()
        t(g.mayRequestNewCapture(), "stop while idle stays idle")
        println("PASS: $checks capture-lifecycle checks")
    }
}
