package com.example.airemote

/** One current JPEG only; no server queues and no persistent screenshot archive. */
data class FrameInfo(val jpeg: ByteArray, val seq: Long, val width: Int,
                     val height: Int, val quality: Int, val profile: Int,
                     val capturedAtMs: Long,
                     val captureEpoch: Long = -1L,
                     val sourcePackage: String? = null,
                     val windowGeneration: Long? = null)

object CaptureOptions {
    /** On-device capture presets: 960, 1280, 1536px. */
    @Volatile var profile: Int = 0 // unchanged seated-player default
    @Volatile var spectatorProfile: Int = 2 // independent high-detail observer preset
    val longEdges = intArrayOf(960, 1280, 1536)
    val qualities = intArrayOf(75, 88, 88)
}

/**
 * A JPEG backing a live ML Kit job must remain current long enough to finish.
 * Raw ImageReader callbacks and card/turn inspection continue independently.
 * OCR is bounded to 1500 ms; replacement JPEGs wait at least 1800 ms.
 */
object CapturePreviewCadence {
    const val MIN_JPEG_REPLACEMENT_MS = 1_800L

    fun mayReplace(nowMs: Long, lastEncodeMs: Long, profileChanged: Boolean): Boolean =
        profileChanged || lastEncodeMs <= 0L ||
            (nowMs >= lastEncodeMs && nowMs - lastEncodeMs >= MIN_JPEG_REPLACEMENT_MS)
}

object FrameStore {
    private val epochCounter = java.util.concurrent.atomic.AtomicLong(0L)
    val captureEpoch: Long get() = epochCounter.get()
    @Volatile var latest: FrameInfo? = null
    // Historical operator preview only. NEVER consumed by OCR, strategy or gestures.
    private var operatorPreview: FrameInfo? = null
    @Volatile var receivedImages: Long = 0L

    @Synchronized fun noteImage(expectedEpoch: Long) {
        if (isCapturing && captureEpoch == expectedEpoch) receivedImages++
    }

    @Synchronized fun rememberOperatorPreview(frame: FrameInfo): Boolean {
        if (!isCapturing || frame.captureEpoch != captureEpoch || latest !== frame) return false
        operatorPreview = frame
        return true
    }

    @Synchronized fun previewForOperator(nowMs: Long, stopped: Boolean): FrameInfo? {
        val frame = operatorPreview ?: return null
        return frame.takeIf { !stopped && isCapturing && it.captureEpoch == captureEpoch &&
            nowMs >= it.capturedAtMs && nowMs - it.capturedAtMs <= 60_000L }
    }

    /** OCR is tied to the exact JPEG and window generation, not the allocation
     * identity of a root assessment refreshed by the next raw image callback. */
    @Synchronized fun commitOcrIfCurrent(
        ticket: OcrFrameTicket, nowMs: Long, allowed: () -> Boolean, publish: () -> Unit
    ): Boolean {
        if (!isCapturing || OcrFrameProvenance.check(ticket, latest, windowOrigin,
                captureEpoch, nowMs, false) != OcrFrameCheck.VALID_DIAGNOSTIC || !allowed()) return false
        publish()
        return true
    }
    @Volatile var lastAutomaticOcr: AutomaticOcrEvidence? = null
    @Volatile var lastSeatVisual: SeatVisualReport? = null
    @Volatile var lastRealFrameRegions: RegionObservationReport? = null
    @Volatile var lastActionStrip: ActionStripReport? = null
    @Volatile var lastHeroTurnEpisode: HeroTurnLifecycleReport? = null
    val heroTurnLifecycle = HeroTurnLifecycleDiagnostics()
    @Volatile var isCapturing: Boolean = false
    @Volatile var frameSequence: Long = 0L
    @Volatile var captureError: String = ""
    @Volatile var localFrameHealth: OnDeviceFrameHealth? = null
    @Volatile var lastCards: CardVisionSignal? = null
    val cardConsensus = CardReadingConsistency()
    /** Strictly read-only: separate from cardConsensus and all hero observations. */
    val spectatorConsensus = SpectatorBoardConsensus()
    @Volatile var spectatorBoard: StableSpectatorBoard? = null
    @Volatile var spectatorBoardForPreview: StableSpectatorBoard? = null
    @Volatile var windowOrigin: WindowOriginAssessment? = null
    // Read-only display diagnostics; never a permission to send a tap.
    @Volatile var lastTurnVisual: TurnVisualSignal? = null
    val turnConsensus = TurnDisplayConsistency()
    @Volatile var confirmedTurnVisual: ConfirmedTurnDisplay? = null
    @Volatile var confirmedCards: StableCardReading? = null
    @Volatile var lastConfirmedForPreview: StableCardReading? = null
    /** Erase all visual conclusions on a lost/mixed foreground without changing capture epoch. */
    @Synchronized fun invalidateVisualEvidence() {
        latest = null
        lastAutomaticOcr = null
        lastSeatVisual = null
        lastRealFrameRegions = null
        lastActionStrip = null
        lastHeroTurnEpisode = null
        heroTurnLifecycle.reset()
        OnDeviceRealFrameRegions.resetSession()
        OnDeviceSeatVisualDiagnostics.reset()
        localFrameHealth = null
        lastCards = null
        confirmedCards = null
        lastConfirmedForPreview = null
        cardConsensus.reset()
        spectatorBoard = null
        spectatorConsensus.reset()
        lastTurnVisual = null
        confirmedTurnVisual = null
        turnConsensus.reset()
        windowOrigin = null
    }
    /**
     * A capture worker must not publish after clear()/STOP or an origin change.
     * Validate and mutate under the SAME lock as clear()/invalidateVisualEvidence().
     * The caller's allowed predicate must re-check STOP, activity visibility and
     * the live Accessibility root while this lock is held. Diagnostics only.
     */
    @Synchronized fun commitIfCurrent(
        expectedEpoch: Long,
        expectedOrigin: WindowOriginAssessment,
        allowed: () -> Boolean,
        publish: () -> Unit
    ): Boolean {
        if (!isCapturing || captureEpoch != expectedEpoch ||
            !expectedOrigin.safeForLocalDiagnostics || windowOrigin !== expectedOrigin ||
            !allowed()) return false
        publish()
        return true
    }

    /**
     * A visible in-game history/statistics panel covers the table. Old cards,
     * OCR, turn buttons and cached JPEG must not survive an occluded frame.
     * Keep the captured window and operator-approved geometry; preserve only
     * long-term, already-verified history. In-flight OCR fails provenance since
     * latest is erased. This is a read-only diagnostic invalidation.
     */
    @Synchronized fun invalidateOccludedSurface(
        expectedEpoch: Long, expectedOrigin: WindowOriginAssessment
    ): Boolean {
        if (!isCapturing || captureEpoch != expectedEpoch ||
            windowOrigin !== expectedOrigin) return false
        latest = null
        lastAutomaticOcr = null
        lastSeatVisual = null
        lastRealFrameRegions = null
        lastActionStrip = null
        lastHeroTurnEpisode = null
        heroTurnLifecycle.reset()
        OnDeviceRealFrameRegions.resetSession()
        OnDeviceSeatVisualDiagnostics.reset()
        localFrameHealth = null
        lastCards = null
        confirmedCards = null
        lastConfirmedForPreview = null
        cardConsensus.reset()
        spectatorBoard = null
        spectatorConsensus.reset()
        lastTurnVisual = null
        confirmedTurnVisual = null
        turnConsensus.reset()
        return true
    }

    /** Starting a service cannot resurrect a capture after the operator cleared it. */
    @Synchronized fun beginActive(expectedEpoch: Long): Boolean {
        if (isCapturing || captureEpoch != expectedEpoch) return false
        isCapturing = true
        return true
    }

    /** Do not resurrect an origin after clear() increments the capture epoch. */
    @Synchronized fun observeWindow(expectedEpoch: Long, assessment: WindowOriginAssessment): Boolean {
        if (!isCapturing || captureEpoch != expectedEpoch ||
            assessment.captureEpoch != expectedEpoch) return false
        if (!assessment.safeForLocalDiagnostics) invalidateVisualEvidence()
        windowOrigin = assessment
        return true
    }

    @Synchronized fun clear() {
        epochCounter.incrementAndGet()
        operatorPreview = null
        spectatorBoardForPreview = null
        receivedImages = 0L
        invalidateVisualEvidence()
        isCapturing = false
    }
}
