package com.example.airemote

import android.content.Context
import android.os.SystemClock

/**
 * Runs ML Kit from live, committed capture frames; at most one job is in flight.
 * This is observation only: it NEVER constructs an AutonomousObservation or gesture.
 * Final publication is guarded by the same FrameStore lock as STOP and clear().
 */
object OnDeviceAutomaticOcr {
    private val assembler = AutomaticOcrEvidenceAssembler()
    private const val INTERVAL_MS = 1_500L
    private var inFlight = false
    private var lastStartedAt = -INTERVAL_MS
    private var lastEpoch = -1L

    @Synchronized fun reset() {
        // The in-flight ML Kit operation may finish later; provenance will reject it.
        assembler.reset()
        OnDeviceSeatVisualDiagnostics.reset()
        OnDeviceSeatRoundVisualDiagnostics.reset()
        OnDeviceRealFrameRegions.resetSession()
        lastStartedAt = -INTERVAL_MS
        lastEpoch = -1L
    }

    @Synchronized private fun acquire(epoch: Long, now: Long): Boolean {
        if (lastEpoch != epoch) { assembler.reset(); lastEpoch = epoch; lastStartedAt = -INTERVAL_MS }
        if (inFlight || now < lastStartedAt || now - lastStartedAt < INTERVAL_MS) return false
        inFlight = true
        lastStartedAt = now
        return true
    }

    @Synchronized private fun finish() { inFlight = false }

    fun offer(context: Context, frame: FrameInfo,
              expectedOrigin: WindowOriginAssessment,
              rootStillMatches: () -> Boolean) {
        val ticket = OcrFrameProvenance.ticket(frame, expectedOrigin) ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - frame.capturedAtMs !in 0L..1_000L ||
            !acquire(frame.captureEpoch, now)) return
        val app = context.applicationContext
        try {
            OnDeviceTextRecognition.inspectStructured(frame.jpeg.copyOf()) { response ->
                try {
                    val finishedAt = SystemClock.elapsedRealtime()
                    // All mutations and final provenance checks serialize with STOP.
                    val published = FrameStore.commitOcrIfCurrent(ticket, finishedAt, {
                        !OperatorStopStore.isStopped(app) && rootStillMatches() &&
                            OcrFrameProvenance.check(ticket, FrameStore.latest,
                                FrameStore.windowOrigin, FrameStore.captureEpoch,
                                finishedAt, false) == OcrFrameCheck.VALID_DIAGNOSTIC &&
                            finishedAt - ticket.capturedAtMs in 0L..1_500L
                    }) {
                        response.fold(onSuccess = { snapshot ->
                            val interpretation = FrameTextEvidence.parse(snapshot.lines)
                             // If flop opened before asynchronous OCR finishes, discard the hint.
                             val atCapture = frame.preflopRankHintAtCapture
                             val currentHint = FrameStore.lastPreflopRankHint
                             val samePreflop = atCapture?.allowed == true &&
                                 currentHint?.allowed == true &&
                                 atCapture.captureEpoch == ticket.captureEpoch &&
                                 currentHint.captureEpoch == ticket.captureEpoch &&
                                 currentHint.frameSequence >= atCapture.frameSequence
                             val ranks = PreflopBottomRankText.parse(snapshot.lines, samePreflop)
                             FrameStore.lastBottomRankHint =
                                 if (ranks != null && atCapture != null)
                                     atCapture.copy(bottomTextRanks=ranks) else null
                            // A stage hint must come from fresh cards in this capture epoch.
                            val cards = FrameStore.confirmedCards
                            val stageHint = if (cards != null &&
                                cards.captureEpoch == ticket.captureEpoch &&
                                cards.capturedAtMs in (ticket.capturedAtMs - 1_200L)..ticket.capturedAtMs)
                                OnDeviceStageBridge.lastAssessment else null
                            val result = assembler.inspect(ticket, interpretation,
                                stageHint, FrameStore.confirmedTurnVisual, finishedAt)
                            FrameStore.lastAutomaticOcr = result
                            // Read-only, uncalibrated by default. Never authorizes an action.
                            FrameStore.lastRealFrameRegions = OnDeviceRealFrameRegions.inspect(
                                ticket, frame.width, frame.height, finishedAt,
                                snapshot.lines, OperatorStopStore.isStopped(app))
                            FrameStore.lastActionStrip = OnDeviceActionStripDiagnostics.inspect(
                                ticket, frame.width, frame.height, finishedAt, snapshot.lines,
                                FrameStore.confirmedTurnVisual, OperatorStopStore.isStopped(app))
                            FrameStore.lastSeatVisual = OnDeviceSeatVisualDiagnostics.inspect(
                                ticket, snapshot.lines, frame.width, frame.height, finishedAt)
                            FrameStore.lastSeatRound = OnDeviceSeatRoundVisualDiagnostics.inspect(
                                frame, ticket, finishedAt,
                                FrameStore.lastRealFrameRegions, FrameStore.lastSeatVisual,
                                OperatorStopStore.isStopped(app))
                        }, onFailure = {
                            assembler.reset()
                            FrameStore.lastAutomaticOcr = null
                            FrameStore.lastSeatVisual = null
                            FrameStore.lastSeatRound = null
                            OnDeviceSeatRoundVisualDiagnostics.reset()
                            FrameStore.lastRealFrameRegions = null
                            FrameStore.lastActionStrip = null
                        })
                    }
                    if (!published) {
                        OnDeviceIncidentRecorder.record(app, IncidentCode.OCR_STALE,
                            IncidentSeverity.WARNING, ticket.captureEpoch, ticket.frameSequence,
                            finishedAt - ticket.capturedAtMs)
                    }
                    if (published && response.isFailure) {
                        OnDeviceIncidentRecorder.record(app, IncidentCode.OCR_ENGINE_ERROR,
                            IncidentSeverity.ERROR, ticket.captureEpoch, ticket.frameSequence)
                    }
                    if (published && PlayerOcrIncidentPolicy.conflictWorthLogging(
                            FrameStore.lastAutomaticOcr)) {
                        OnDeviceIncidentRecorder.record(app, IncidentCode.OCR_CONFLICT,
                            IncidentSeverity.WARNING, ticket.captureEpoch, ticket.frameSequence)
                    }
                    if (published && response.isSuccess &&
                        !CaptureObservationMode.isSpectator() &&
                        ticket.captureEpoch == FrameStore.captureEpoch &&
                        !OperatorStopStore.isStopped(app)) {
                        response.getOrNull()?.let { snapshot ->
                            PlayerVisionJournal.observeOcr(app,
                                FrameTextEvidence.parse(snapshot.lines), ticket.captureEpoch,
                                snapshot.lines)
                        }
                    }
                    // Source validation is complete. Only a strictly zero-chip
                    // CHECK may use the Canvas OCR+colour+cards pilot. No CALL,
                    // FOLD, BET, RAISE, inferred hand ID or remote GPT command.
                    if (published && response.isSuccess &&
                        LocalPlayerCheckPilot.isActive() &&
                        !CaptureObservationMode.isSpectator() &&
                        LiveGamePermissionGate.permitted() &&
                        !OperatorStopStore.isStopped(app) &&
                        ticket.captureEpoch == FrameStore.captureEpoch) {
                        val foreground = LiveForegroundProbe.snapshot()
                        val origin = FrameStore.windowOrigin
                        if (foreground != null && origin != null &&
                            foreground.packageName == ticket.sourcePackage &&
                            foreground.windowGeneration == ticket.windowGeneration &&
                            origin.windowGeneration == ticket.windowGeneration &&
                            LiveGamePermissionGate.packageName() == ticket.sourcePackage) {
                            // Use the card consensus snapshotted alongside
                            // THIS JPEG, not cards from an arbitrary newer raw
                            // frame after the asynchronous ML Kit callback.
                            val visualTicket = LocalPlayerCheckPilot.offerVisualOcr(
                                frame.playerCardsAtCapture,
                                FrameStore.lastAutomaticOcr, ticket.captureEpoch,
                                finishedAt, app.resources.displayMetrics.widthPixels,
                                app.resources.displayMetrics.heightPixels)
                            if (visualTicket != null)
                                LiveForegroundProbe.activeService()?.offerScopedVisualCheck(visualTicket)
                        }
                    }
                    // Table exit is separate from wagering: the candidate occupancy
                    // reading is useful only with full measured seat evidence
                    // and an independently VERIFIED between-hands stage.
                    if (published && response.isSuccess && !CaptureObservationMode.isSpectator() &&
                        !OperatorStopStore.isStopped(app)) {
                        FrameStore.lastRealFrameRegions?.takeIf {
                            it.frameSequence == ticket.frameSequence
                        }?.let {
                            OnDeviceOneWinTableAutoExit.observeSeats(app, ticket, it, finishedAt)
                        }
                    }
                    // Bind a real capture frame to its asynchronous OCR result.
                    // Advisor input is read-only, not a game move or remote call.
                    if (published && response.isSuccess && !CaptureObservationMode.isSpectator()) {
                        synchronized(FrameStore) {
                            val current = FrameStore.latest
                            val ocr = FrameStore.lastAutomaticOcr
                            if (current === frame && ocr?.ticket == ticket &&
                                !OperatorStopStore.isStopped(app)) {
                                LivePlayerModelContextAssembler.fromFrame(
                                    frame, ocr, finishedAt, false)?.let { sample ->
                                    OnDevicePlayerContextBuffer.memory.offer(sample)
                                }
                            }
                        }
                    }
                    if (published) {
                        // Forward the read-only observation to the autonomy coordinator,
                        // but never manufacture table/hand/turn identity or action rights.
                        FrameStore.lastAutomaticOcr?.takeIf {
                            it.ticket == ticket && !it.automationReady
                        }?.let { OnDeviceAutonomousRuntime.acceptAutomaticDiagnostic(app, it) }
                    }
                } finally { finish() }
            }
        } catch (_: Exception) {
            finish()
            OnDeviceIncidentRecorder.record(app, IncidentCode.OCR_ENGINE_ERROR,
                IncidentSeverity.ERROR, ticket.captureEpoch, ticket.frameSequence)
        }
    }
}
