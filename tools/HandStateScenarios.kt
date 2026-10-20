import com.example.airemote.*

/** Executable Kotlin/JVM tests without Android SDK. */
object HandStateScenarios {
    private var passed = 0
    private fun verify(condition: Boolean, name: String) {
        check(condition) { "FAILED: $name" }
        passed++
    }

    private class Stream(val tracker: HandStateTracker = HandStateTracker()) {
        var seq = 0L
        var now = 1000L
        var epoch = 1L
        var table = "trusted-table-1"
        fun push(
            hole: List<String>? = listOf("As", "Kh"),
            board: List<String>? = emptyList(),
            confidence: Double = 0.99,
            sourceVerified: Boolean = true,
            waiting: Boolean = false,
            showdown: Boolean = false,
            finished: Boolean = false,
            newHandEvent: String? = null,
            token: String? = "hand-A",
            ageMs: Long = 100L,
            delayMs: Long = 350L
        ): HandStateSnapshot {
            now += delayMs
            return tracker.ingest(VerifiedTableObservation(
                table, epoch, ++seq, now - ageMs, now, sourceVerified,
                confidence, hole, board, waiting, showdown, finished, newHandEvent, token
            ))
        }
        fun confirm(hole: List<String>? = listOf("As", "Kh"), board: List<String>? = emptyList(),
                    waiting: Boolean = false, showdown: Boolean = false, finished: Boolean = false,
                    token: String? = "hand-A", newHandEvent: String? = null): HandStateSnapshot {
            push(hole, board, waiting=waiting, showdown=showdown, finished=finished,
                 token=token, newHandEvent=newHandEvent)
            return push(hole, board, waiting=waiting, showdown=showdown, finished=finished,
                token=token, newHandEvent=newHandEvent)
        }
    }

    @JvmStatic fun main(args: Array<String>) {
        val a = Stream()
        verify(a.tracker.snapshot().stage == HandStage.UNKNOWN && !a.tracker.snapshot().trustworthyNow,
            "Initial state untrusted")
        verify(!a.push().trustworthyNow, "one frame is insufficient")
        verify(a.push().stage == HandStage.PREFLOP && a.tracker.snapshot().trustworthyNow,
            "two frames confirm preflop")
        verify(a.tracker.snapshot().handSerial == 1L, "first hand serial")
        verify(a.push().trustworthyNow, "stable observations do not intermittently invalidate")
        verify(!a.tracker.snapshot(a.now + 1600L).trustworthyNow,
            "silence after capture makes decision context untrustworthy")
        verify(a.tracker.snapshot(a.now + 1600L).stage == HandStage.PREFLOP,
            "expired trust retains last phase only for diagnostics")
        verify(!a.push(board=listOf("Qs", "Jd", "2h")).trustworthyNow, "flop pending blocks")
        verify(a.push(board=listOf("Qs", "Jd", "2h")).stage == HandStage.FLOP &&
            a.tracker.snapshot().trustworthyNow, "flop accepted")
        verify(a.confirm(board=listOf("Qs", "Jd", "2h", "6c")).stage == HandStage.TURN,
            "turn accepted")
        verify(a.confirm(board=listOf("Qs", "Jd", "2h", "6c", "9h")).stage == HandStage.RIVER,
            "river accepted")
        verify(a.confirm(board=listOf("Qs", "Jd", "2h", "6c", "9h"),showdown=true)
            .stage == HandStage.SHOWDOWN, "showdown accepted")
        verify(a.confirm(board=emptyList(),hole=emptyList(),finished=true).stage == HandStage.FINISHED,
            "hand end accepted")
        verify(a.confirm(board=emptyList(),hole=emptyList(),waiting=true).stage == HandStage.WAITING,
            "waiting accepted")
        verify(a.confirm(hole=listOf("Qc", "Qd"),board=emptyList(),token="hand-B")
            .stage == HandStage.PREFLOP && a.tracker.snapshot().handSerial == 2L,
            "new hand after waiting")

        val b = Stream()
        b.confirm()
        verify(!b.push(board=listOf("Qs", "Jd" )).trustworthyNow,
            "partial flop rejected")
        verify(!b.push(board=listOf("Qs", "Jd", "Qs")).trustworthyNow,
            "duplicate card rejected")
        verify(!b.push(board=listOf("Qh", "Jd", "2h"),hole=listOf("Ah", "Kh"),confidence=.5)
            .trustworthyNow, "low confidence rejected")
        verify(!b.push(sourceVerified=false).trustworthyNow, "unverified source rejected")
        verify(!b.push(ageMs=1800).trustworthyNow, "stale frame rejected")
        verify(!b.push(hole=listOf("Ah", "Kh")).trustworthyNow,
            "hole card changed mid hand rejected")
        verify(b.confirm(board=listOf("Qs", "Jd", "2h")).stage == HandStage.FLOP,
            "valid context restored")
        verify(!b.push(board=emptyList()).trustworthyNow,
            "spontaneous regression to preflop rejected")
        verify(!b.push(board=listOf("Qs", "Td", "2h", "6c")).trustworthyNow,
            "earlier board mismatch rejected")
        verify(!b.push(board=listOf("Qs", "Jd", "2h", "6c"),token="other")
            .trustworthyNow, "hand identity mismatched")
        val frame = b.seq
        val time = b.now
        verify(!b.tracker.ingest(VerifiedTableObservation(b.table,b.epoch,frame,time-100,time,
            true,.99,listOf("As","Kh"),listOf("Qs","Jd","2h"))).trustworthyNow,
            "repeated frame sequence rejected")
        verify(!b.push(board=listOf("Qs", "Jd", "2h", "6c"),delayMs=200).trustworthyNow,
            "need second confirmation after rejection")
        verify(b.push(board=listOf("Qs", "Jd", "2h", "6c")).stage == HandStage.TURN,
            "restored second confirmation")

        val c = Stream()
        c.confirm()
        verify(!c.push(hole=listOf("Qh", "Jc"),token="hand-B").trustworthyNow,
            "new hand missing visible event may still require preflop only")
        // When stage is preflop, a changed token is an explicit hand identity cue.
        verify(c.push(hole=listOf("Qh", "Jc"),token="hand-B").stage == HandStage.PREFLOP &&
            c.tracker.snapshot().handSerial == 2L, "new token starts new hand")
        verify(c.push(hole=listOf("Qh", "Jc"),token="hand-B").handSerial == 2L,
            "persistent new token never loops the hand counter")
        verify(!c.push(hole=listOf("9c", "9d"),token=null,newHandEvent="event-2")
            .trustworthyNow,"new hand marker needs two frames")
        verify(c.push(hole=listOf("9c", "9d"),token=null,newHandEvent="event-2")
            .handSerial == 3L, "unique event starts hand without token")
        verify(c.push(hole=listOf("9c", "9d"),token=null,newHandEvent="event-2")
            .handSerial == 3L, "same event never loops hand counter")
        verify(!c.push(hole=listOf("8c", "8d"),token=null,newHandEvent="event-2")
            .trustworthyNow, "reused marker cannot start a different hand")

        val d = Stream()
        d.confirm()
        d.epoch = 2
        verify(!d.push().trustworthyNow && d.tracker.snapshot().stage == HandStage.UNKNOWN,
            "capture epoch invalidates hand")
        verify(d.push().trustworthyNow, "new capture requires confirmation")
        d.table = "trusted-table-2"
        verify(!d.push().trustworthyNow && d.tracker.snapshot().stage == HandStage.UNKNOWN,
            "table change invalidates hand")
        verify(d.push().trustworthyNow, "new table confirms independently")
        d.tracker.reset()
        verify(!d.tracker.snapshot().trustworthyNow && d.tracker.snapshot().stage == HandStage.UNKNOWN,
            "STOP resets tracker")
        val e = Stream()
        verify(!e.push(hole=emptyList(),board=emptyList()).trustworthyNow,
            "empty board without waiting indicator is not waiting")
        verify(e.confirm(hole=emptyList(),board=emptyList(),waiting=true).stage == HandStage.WAITING,
            "waiting must be positively confirmed")
        val f = Stream()
        f.confirm(board=listOf("Qc","Jd","2h"))
        verify(f.tracker.snapshot().stage == HandStage.FLOP,
            "attach to verified mid-hand stage without inventing prior phases")
        verify(f.confirm(board=listOf("Qc","Jd","2h","6c","9s")).stage == HandStage.RIVER,
            "recover skipped phases from consistent confirmed board")
        println("PASS: $passed independent pure-Kotlin hand state scenarios")
    }
}
