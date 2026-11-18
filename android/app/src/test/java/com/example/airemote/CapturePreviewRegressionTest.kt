package com.example.airemote

import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

class CapturePreviewRegressionTest {
    private var epoch = 0L
    private lateinit var first: WindowOriginAssessment
    private lateinit var frame: FrameInfo
    @Before fun start() {
        FrameStore.clear()
        epoch = FrameStore.captureEpoch
        assertTrue(FrameStore.beginActive(epoch))
        first = origin(2)
        FrameStore.observeWindow(epoch, first)
        frame = FrameInfo(byteArrayOf(1, 2, 3), 1, 2, 2, 75, 0, 1000, epoch, "org.example.game", 4)
        assertTrue(FrameStore.commitIfCurrent(epoch, first, { true }) {
            FrameStore.latest = frame
            FrameStore.rememberOperatorPreview(frame)
        })
    }
    @After fun stop() { FrameStore.clear() }
    private fun origin(seq: Long, generation: Long = 4) = WindowOriginAssessment(
        WindowOriginStatus.EXTERNAL_PACKAGE_STABLE, "org.example.game", epoch, seq, "diagnostic", generation)
    private fun ticket() = requireNotNull(OcrFrameProvenance.ticket(frame, first))

    @Test fun unchangedJpegOcrSurvivesNextRawAssessmentAt612ms() {
        val ticket = ticket()
        FrameStore.observeWindow(epoch, origin(3))
        // Reproduces old failure: the exact assessment allocation has changed.
        assertFalse(FrameStore.commitIfCurrent(epoch, first, { true }) {})
        var committed = false
        assertTrue(FrameStore.commitOcrIfCurrent(ticket, 1612, { true }) { committed = true })
        assertTrue(committed)
    }
    @Test fun replacementJpegRejectsOldOcr() {
        val ticket = ticket()
        FrameStore.latest = frame.copy(seq = 2, capturedAtMs = 1300)
        assertFalse(FrameStore.commitOcrIfCurrent(ticket, 1612, { true }) { fail("old OCR") })
    }
    @Test fun changedRootGenerationAndMissingRootRejectOcr() {
        val ticket = ticket()
        FrameStore.observeWindow(epoch, origin(3, 5))
        assertFalse(FrameStore.commitOcrIfCurrent(ticket, 1500, { true }) { fail("wrong root") })
        FrameStore.observeWindow(epoch, WindowOriginAssessment(WindowOriginStatus.UNKNOWN, null, epoch, 4, "lost"))
        assertFalse(FrameStore.commitOcrIfCurrent(ticket, 1500, { true }) { fail("unknown root") })
    }
    @Test fun operatorPreviewSurvivesEvidenceInvalidationButCannotBecomeLiveEvidence() {
        FrameStore.observeWindow(epoch, WindowOriginAssessment(WindowOriginStatus.UNKNOWN, null, epoch, 3, "returned to operator"))
        assertNull(FrameStore.latest)
        assertNull(FrameStore.lastAutomaticOcr)
        assertSame(frame, FrameStore.previewForOperator(1612, false))
        assertFalse(FrameStore.commitOcrIfCurrent(ticket(), 1612, { true }) { fail("preview is not live") })
    }
    @Test fun stopEpochAgeAndOperatorVetoClearOrDenyPreviewAndOcr() {
        val ticket = ticket()
        assertNull(FrameStore.previewForOperator(1612, true))
        assertNull(FrameStore.previewForOperator(61001, false))
        assertNull(FrameStore.previewForOperator(999, false))
        assertFalse(FrameStore.commitOcrIfCurrent(ticket, 1500, { false }) { fail("STOP veto") })
        FrameStore.clear()
        assertNull(FrameStore.previewForOperator(1612, false))
        assertFalse(FrameStore.commitOcrIfCurrent(ticket, 1612, { true }) { fail("old epoch") })
        assertFalse(FrameStore.rememberOperatorPreview(frame))
    }
    @Test fun jpegCadencePreventsOcrReplacementDuringCallback() {
        assertTrue(CapturePreviewCadence.mayReplace(1000L, 0L, false))
        assertFalse(CapturePreviewCadence.mayReplace(1300L, 1000L, false))
        assertFalse(CapturePreviewCadence.mayReplace(1612L, 1000L, false))
        assertTrue(CapturePreviewCadence.mayReplace(2800L, 1000L, false))
        assertTrue(CapturePreviewCadence.mayReplace(1300L, 1000L, true))
        assertFalse(CapturePreviewCadence.mayReplace(900L, 1000L, false))
    }

    @Test fun rawImageCounterIsEpochScopedAndDoesNotArmOrCreateJpeg() {
        FrameStore.noteImage(epoch)
        assertEquals(1L, FrameStore.receivedImages)
        FrameStore.noteImage(epoch - 1)
        assertEquals(1L, FrameStore.receivedImages)
        FrameStore.clear()
        FrameStore.noteImage(epoch)
        assertEquals(0L, FrameStore.receivedImages)
        assertNull(FrameStore.latest)
        assertFalse(FrameStore.isCapturing)
    }
}
