import com.example.airemote.*

object OcrDiagnosticScenarios {
    @JvmStatic fun main(args: Array<String>) {
        var passed = 0
        fun verify(value: Boolean, label: String) { check(value) { label }; passed++ }
        fun t(seq: Long = 1, at: Long = 1000, ep: Long = 7L, pkg: String = "com.external.test", gen: Long = 2L) =
            OcrFrameTicket(ep, seq, at, pkg, gen)
        val p = ActionLabelCandidate(ExactActionCandidate.FOLD, NormalizedTextBox(90, 945, 180, 972), "Fold")
        val pot = NumericLabelCandidate(NumericCandidateKind.POT, 100, NormalizedTextBox(300, 400, 530, 430), "Pot:100")
        val timer = NumericLabelCandidate(NumericCandidateKind.TIMER_SECONDS, 19, NormalizedTextBox(300, 800, 530, 830), "Timer:19")
        val reading = FrameTextInterpretation(listOf(p), listOf(pot, timer), emptyList())
        fun read(potSize: Long = 100, seconds: Long = 19, errors: List<String> = emptyList()) =
            reading.copy(numbers = listOf(pot.copy(amount = potSize), timer.copy(amount = seconds)), rejected = errors)
        fun fresh() = OcrDiagnosticConsensus()
        val c = fresh()
        verify(c.observe(t(), reading).status == OcrDiagnosticStatus.WAITING_SECOND_FRAME, "First frame only")
        verify(c.observe(t(seq = 2, at = 1800), read(seconds = 18)).status ==
            OcrDiagnosticStatus.CONSISTENT_DIAGNOSTIC_ONLY, "Two distinct consistent frames")
        verify(c.observe(t(seq = 3, at = 2300), read(seconds = 18)).status ==
            OcrDiagnosticStatus.CONSISTENT_DIAGNOSTIC_ONLY, "Three matching frames")
        verify(c.observe(t(seq = 3, at = 2350), read(seconds = 18)).status ==
            OcrDiagnosticStatus.REJECTED, "Duplicate sequence not a new seed")
        verify(c.observe(t(seq = 4, at = 2500), reading).status ==
            OcrDiagnosticStatus.WAITING_SECOND_FRAME, "Restart after bad ordering")
        val switch = fresh()
        switch.observe(t(), reading)
        verify(switch.observe(t(seq=2, at=1800, gen=3), reading).status == OcrDiagnosticStatus.REJECTED, "Window switch")
        verify(switch.observe(t(seq=3, at=2000, gen=3), reading).status == OcrDiagnosticStatus.WAITING_SECOND_FRAME, "New root starts fresh")
        val other = fresh()
        other.observe(t(), reading)
        verify(other.observe(t(seq=2, at=1800, pkg="com.other.test"), reading).status == OcrDiagnosticStatus.REJECTED, "Other app")
        val epoch = fresh()
        epoch.observe(t(), reading)
        verify(epoch.observe(t(seq=2, at=1800, ep=8), reading).status == OcrDiagnosticStatus.REJECTED, "New capture")
        val old = fresh()
        old.observe(t(), reading)
        verify(old.observe(t(seq=2, at=5000), reading).status == OcrDiagnosticStatus.REJECTED, "Large gap")
        val unordered = fresh()
        unordered.observe(t(), reading)
        verify(unordered.observe(t(seq=2, at=990), reading).status == OcrDiagnosticStatus.REJECTED, "Clock regression")
        val changed = fresh()
        changed.observe(t(), reading)
        verify(changed.observe(t(seq=2, at=1800), read(potSize=120, seconds=18)).status ==
            OcrDiagnosticStatus.WAITING_SECOND_FRAME, "Pot change is not agreement")
        verify(changed.observe(t(seq=3, at=2400), read(potSize=120, seconds=17)).status ==
            OcrDiagnosticStatus.CONSISTENT_DIAGNOSTIC_ONLY, "Fresh changed pot may be consistent")
        val timerBack = fresh()
        timerBack.observe(t(), reading)
        verify(timerBack.observe(t(seq=2, at=1800), read(seconds=20)).status ==
            OcrDiagnosticStatus.WAITING_SECOND_FRAME, "Growing countdown rejected")
        val timerSkip = fresh()
        timerSkip.observe(t(), reading)
        verify(timerSkip.observe(t(seq=2, at=1800), read(seconds=5)).status ==
            OcrDiagnosticStatus.WAITING_SECOND_FRAME, "Implausible countdown rejected")
        val errors = fresh()
        verify(errors.observe(t(), read(errors=listOf("ambiguous"))).status == OcrDiagnosticStatus.REJECTED, "Rejected parse cannot seed")
        verify(errors.observe(t(), FrameTextInterpretation(emptyList(), emptyList(), emptyList())).status ==
            OcrDiagnosticStatus.REJECTED, "Empty observation")
        val duplicate = fresh()
        verify(duplicate.observe(t(), reading.copy(actions=listOf(p, p))).status ==
            OcrDiagnosticStatus.REJECTED, "Duplicate action candidate")
        val noTimer = fresh()
        noTimer.observe(t(), reading.copy(numbers=listOf(pot)))
        verify(noTimer.observe(t(seq=2, at=1800), reading).status ==
            OcrDiagnosticStatus.WAITING_SECOND_FRAME, "Timer appeared")
        val safe = fresh()
        safe.observe(t(), reading)
        safe.reset()
        verify(safe.observe(t(seq=2, at=1800), reading).status ==
            OcrDiagnosticStatus.WAITING_SECOND_FRAME, "Reset revokes consensus")
        println("OcrDiagnosticScenarios: $passed checks passed")
    }
}
