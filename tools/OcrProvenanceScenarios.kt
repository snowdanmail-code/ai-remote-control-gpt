import com.example.airemote.*

object OcrProvenanceScenarios {
    @JvmStatic fun main(args: Array<String>) {
        var passed = 0
        fun verify(value: Boolean, label: String) { check(value) { label }; passed++ }
        val origin = WindowOriginAssessment(WindowOriginStatus.EXTERNAL_PACKAGE_STABLE,
            "com.external.test", 7L, 13L, "Два кадра", 22L)
        val frame = FrameInfo(byteArrayOf(1), 8L, 480, 960, 88, 1, 2000L,
            7L, "com.external.test", 22L)
        val ticket = OcrFrameProvenance.ticket(frame, origin)
        verify(ticket != null, "Verified frame/root creates diagnostic ticket")
        fun result(f: FrameInfo? = frame, o: WindowOriginAssessment? = origin,
                   epoch: Long = 7L, now: Long = 2600L, stop: Boolean = false) =
            OcrFrameProvenance.check(ticket, f, o, epoch, now, stop)
        verify(result() == OcrFrameCheck.VALID_DIAGNOSTIC, "Fresh unchanged context")
        verify(result(stop = true) == OcrFrameCheck.REJECTED, "STOP")
        verify(result(epoch = 8) == OcrFrameCheck.REJECTED, "Epoch changed")
        verify(result(now = 10001) == OcrFrameCheck.REJECTED, "Age expired")
        verify(result(now = 1999) == OcrFrameCheck.REJECTED, "Future frame")
        verify(result(f = frame.copy(seq = 9)) == OcrFrameCheck.REJECTED, "New JPEG sequence")
        verify(result(f = frame.copy(capturedAtMs = 1999)) == OcrFrameCheck.REJECTED, "Different capture timestamp")
        verify(result(f = frame.copy(windowGeneration = 23)) == OcrFrameCheck.REJECTED, "Same package but different frame window")
        verify(result(o = origin.copy(windowGeneration = 23)) == OcrFrameCheck.REJECTED, "Same package modal window")
        verify(result(o = origin.copy(observedPackage = "com.external.other")) == OcrFrameCheck.REJECTED, "Changed package")
        verify(result(o = origin.copy(status = WindowOriginStatus.UNKNOWN)) == OcrFrameCheck.REJECTED, "Unknown origin")
        verify(result(o = origin.copy(captureEpoch = 8L)) == OcrFrameCheck.REJECTED, "Origin epoch")
        verify(result(f = null) == OcrFrameCheck.REJECTED, "No current frame")
        verify(OcrFrameProvenance.ticket(frame.copy(sourcePackage = null), origin) == null, "Absent frame source")
        verify(OcrFrameProvenance.ticket(frame.copy(captureEpoch = -1), origin) == null, "Legacy unbound JPEG")
        verify(OcrFrameProvenance.ticket(frame, origin.copy(windowGeneration = null)) == null, "Legacy origin without root generation")
        verify(OcrFrameProvenance.ticket(frame, origin.copy(status = WindowOriginStatus.UNKNOWN)) == null, "Unstable source")
        verify(OcrFrameProvenance.ticket(frame.copy(sourcePackage = "com.external.other"), origin) == null, "Wrong package")
        println("OcrProvenanceScenarios: $passed checks passed")
    }
}
