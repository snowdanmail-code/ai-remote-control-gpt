import com.example.airemote.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Pure JVM regression scenarios for atomic visual publication; no Android or gestures. */
object FramePublicationScenarios {
    @JvmStatic fun main(args: Array<String>) {
        var cases = 0
        fun ok(value: Boolean, why: String) { check(value) { why }; cases++ }
        fun origin(epoch: Long, seq: Long = 2, generation: Long = 4L) = WindowOriginAssessment(
            WindowOriginStatus.EXTERNAL_PACKAGE_STABLE, "org.example.game", epoch, seq,
            "diagnostic only", generation)
        fun frame(epoch: Long, seq: Long) = FrameInfo(byteArrayOf(1, 2, 3), seq, 2, 2,
            75, 0, 1000L, epoch, "org.example.game", 4L)
        FrameStore.clear()
        val e = FrameStore.captureEpoch
        val source = origin(e)
        ok(!FrameStore.observeWindow(e, source), "closed store rejects origin before capture")
        ok(FrameStore.beginActive(e), "capture becomes active in original epoch")
        ok(!FrameStore.beginActive(e), "double activation rejected")
        ok(FrameStore.observeWindow(e, source), "active epoch admits observed window")
        ok(FrameStore.windowOrigin === source, "origin has exact publication identity")
        ok(!FrameStore.commitIfCurrent(e, source, { false }) { error("published when stopped") },
            "STOP predicate vetoes publication")
        ok(FrameStore.latest == null, "a veto cannot install a JPEG")
        ok(FrameStore.commitIfCurrent(e, source, { true }) {
            FrameStore.latest = frame(e, 1)
            FrameStore.frameSequence = 1
        }, "valid diagnostic frame may be published")
        ok(FrameStore.latest?.seq == 1L, "frame committed once")
        ok(!FrameStore.commitIfCurrent(e, origin(e), { true }) { error("foreign origin") },
            "same-valued but unobserved window cannot publish")
        val updated = origin(e, seq = 3)
        ok(FrameStore.observeWindow(e, updated), "new frame origin is accepted")
        ok(!FrameStore.commitIfCurrent(e, source, { true }) { error("old window") },
            "old origin cannot publish after a newer root sample")
        val unknown = WindowOriginAssessment(WindowOriginStatus.UNKNOWN, null, e, 4, "lost root")
        ok(FrameStore.observeWindow(e, unknown), "unknown origin published as diagnostic failure")
        ok(FrameStore.latest == null, "losing the root erases the JPEG")
        ok(!FrameStore.commitIfCurrent(e, unknown, { true }) { error("unknown") },
            "unknown root never permits data")
        ok(FrameStore.observeWindow(e, updated), "new safe origin can be observed")
        FrameStore.clear()
        ok(!FrameStore.isCapturing && FrameStore.latest == null && FrameStore.windowOrigin == null,
            "STOP synchronously clears both JPEG and origin")
        ok(!FrameStore.observeWindow(e, source), "stale worker cannot revive old origin")
        ok(!FrameStore.commitIfCurrent(e, source, { true }) { error("after stop") },
            "stale worker cannot write after STOP")
        val newEpoch = FrameStore.captureEpoch
        ok(!FrameStore.beginActive(e), "old capture cannot activate after STOP")
        ok(FrameStore.beginActive(newEpoch), "fresh capture activation")
        val newSource = origin(newEpoch)
        ok(FrameStore.observeWindow(newEpoch, newSource), "fresh epoch is allowed to start")
        ok(!FrameStore.commitIfCurrent(e, source, { true }) { error("cross epoch") },
            "older capture cannot write into new session")
        ok(FrameStore.commitIfCurrent(newEpoch, newSource, { true }) {
            FrameStore.latest = frame(newEpoch, 2)
        }, "current generation still works")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val stopReturned = AtomicBoolean(false)
        val writer = thread(name = "publication") {
            FrameStore.commitIfCurrent(newEpoch, newSource, { true }) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                FrameStore.latest = frame(newEpoch, 3)
            }
        }
        ok(entered.await(5, TimeUnit.SECONDS), "writer reached transaction")
        val stopper = thread(name = "stopper") {
            FrameStore.clear()
            stopReturned.set(true)
        }
        Thread.sleep(25)
        ok(!stopReturned.get(), "STOP waits for in-flight atomic mutation")
        release.countDown()
        writer.join(5000)
        stopper.join(5000)
        ok(!writer.isAlive && !stopper.isAlive, "race resolves without deadlock")
        ok(stopReturned.get(), "stop finished")
        ok(FrameStore.latest == null && FrameStore.windowOrigin == null,
            "no frame resurrected once STOP returns")
        ok(!FrameStore.commitIfCurrent(newEpoch, newSource, { true }) { error("late") },
            "late worker never publishes after completed STOP")
        FrameStore.clear()
        println("FramePublicationScenarios: $cases checks passed")
    }
}
