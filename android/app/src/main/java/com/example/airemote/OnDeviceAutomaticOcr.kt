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
                        }, onFailure = {
                            assembler.reset()
                            FrameStore.lastAutomaticOcr = null
                            FrameStore.lastSeatVisual = null
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
                    if (published && FrameStore.lastAutomaticOcr?.agreement?.status ==
                        OcrDiagnosticStatus.REJECTED) {
                        OnDeviceIncidentRecorder.record(app, IncidentCode.OCR_CONFLICT,
                            IncidentSeverity.WARNING, ticket.captureEpoch, ticket.frameSequence)
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
