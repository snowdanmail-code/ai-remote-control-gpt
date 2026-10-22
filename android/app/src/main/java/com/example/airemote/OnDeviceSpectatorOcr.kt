package com.example.airemote

import android.content.Context
import android.os.SystemClock

/**
 * Completely separate ML Kit branch. Spectator OCR cannot assemble player
 * observations, identify opponent actions or feed AutonomyRuntime.
 * At most one in-flight OCR; stale/STOP/foreground changes fail closed.
 */
object OnDeviceSpectatorOcr {
    private const val MIN_INTERVAL_MS = 1_800L
    private var inFlight = false
    private var startedMs = -MIN_INTERVAL_MS
    private var epoch = -1L

    @Synchronized fun reset() { startedMs = -MIN_INTERVAL_MS; epoch = -1L }

    @Synchronized private fun acquire(expectedEpoch: Long, nowMs: Long): Boolean {
        if (epoch != expectedEpoch) { epoch = expectedEpoch; startedMs = -MIN_INTERVAL_MS }
        if (inFlight || nowMs < startedMs ||
            nowMs - startedMs < MIN_INTERVAL_MS) return false
        inFlight = true
        startedMs = nowMs
        return true
    }

    @Synchronized private fun finish() { inFlight = false }

    fun offer(context: Context, frame: FrameInfo,
              origin: WindowOriginAssessment, stillSameRoot: () -> Boolean) {
        if (!CaptureObservationMode.isSpectator()) return
        val ticket = OcrFrameProvenance.ticket(frame, origin) ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - frame.capturedAtMs !in 0L..1_000L ||
            !acquire(frame.captureEpoch, now)) return
        val app = context.applicationContext
        try {
            OnDeviceTextRecognition.inspectStructured(frame.jpeg.copyOf()) { outcome ->
                try {
                    val doneMs = SystemClock.elapsedRealtime()
                    var hint: SpectatorNumericHint? = null
                    val published = FrameStore.commitOcrIfCurrent(ticket, doneMs, {
                        CaptureObservationMode.isSpectator() &&
                            !OperatorStopStore.isStopped(app) &&
                            stillSameRoot() &&
                            doneMs - ticket.capturedAtMs in 0L..1_500L
                    }) {
                        outcome.onSuccess { result ->
                            val parsed = FrameTextEvidence.parse(result.lines)
                            val pot = parsed.numbers.singleOrNull { it.kind ==
                                NumericCandidateKind.POT }?.amount
                            val call = parsed.numbers.singleOrNull { it.kind ==
                                NumericCandidateKind.CALL_AMOUNT }?.amount
                            hint = SpectatorNumericHint(ticket.captureEpoch,
                                ticket.frameSequence, pot, call, parsed.rejected.size, doneMs)
                            FrameStore.spectatorNumericHint = hint
                        }
                    }
                    if (published && hint != null &&
                        CaptureObservationMode.isSpectator() &&
                        !OperatorStopStore.isStopped(app) &&
                        ticket.captureEpoch == FrameStore.captureEpoch) {
                        SpectatorLearningJournal.observeNumericHint(app, requireNotNull(hint))
                    }
                    if (published && outcome.isFailure)
                        OnDeviceIncidentRecorder.record(app, IncidentCode.OCR_ENGINE_ERROR,
                            IncidentSeverity.WARNING, ticket.captureEpoch, ticket.frameSequence)
                } finally { finish() }
            }
        } catch (_: Exception) {
            finish()
            OnDeviceIncidentRecorder.record(app, IncidentCode.OCR_ENGINE_ERROR,
                IncidentSeverity.WARNING, ticket.captureEpoch, ticket.frameSequence)
        }
    }
}
