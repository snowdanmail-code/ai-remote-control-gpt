package com.example.airemote

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import java.io.ByteArrayOutputStream

/**
 * Separate read-only OCR when the recorded 1win appearance indicates an
 * in-game opponent/history popup. Gameplay cards, turn state and buttons
 * remain INVALIDATED throughout the popup.
 *
 * The parser has NO avatar/seat/player identity: values must NOT be merged
 * into durable opponent memory or sent to GPT until the clicked avatar and
 * resulting popup are independently linked and confirmed.
 */
data class OpponentPopupStatsSnapshot(
    val sourcePackage: String,
    val captureEpoch: Long,
    val windowGeneration: Long,
    val firstCapturedAtMs: Long,
    val confirmedAtMs: Long,
    val metrics: List<OpponentPopupMetricValue>,
    val displayedNickname: String? = null
) {
    val linkedOpponentKey: String? get() = null
    val authorizesAction: Boolean get() = false
}

object OnDeviceOpponentPopupOcr {
    private const val INTERVAL_MS = 1_500L
    private const val MAX_AGE_MS = 2_500L

    private data class PanelCandidate(
        val sourcePackage: String,
        val captureEpoch: Long,
        val windowGeneration: Long,
        val frameSeq: Long,
        val capturedAtMs: Long,
        val values: List<OpponentPopupMetricValue>,
        val nickname: String?
    )

    private var generation = 0L
    private var inFlight = false
    private var previous: PanelCandidate? = null
    private var lastStartedAt = -INTERVAL_MS
    @Volatile private var confirmed: OpponentPopupStatsSnapshot? = null

    @Synchronized fun latest(): OpponentPopupStatsSnapshot? = confirmed

    @Synchronized fun reset() {
        generation++
        inFlight = false
        previous = null
        confirmed = null
        lastStartedAt = -INTERVAL_MS
    }

    /**
     * Called from the capture worker ONLY for PANEL_VISIBLE frames, never for
     * an unknown window. No screenshot is uploaded; ML Kit runs on the phone.
     */
    fun offer(context: Context, source: Bitmap,
              origin: WindowOriginAssessment, nowMs: Long,
              stillSamePanel: () -> Boolean) {
        if (!origin.safeForLocalDiagnostics ||
            origin.observedPackage.isNullOrBlank() || origin.windowGeneration == null ||
            OperatorStopStore.isStopped(context)) return
        val token: Long
        synchronized(this) {
            if (inFlight || nowMs - lastStartedAt < INTERVAL_MS) return
            inFlight = true
            lastStartedAt = nowMs
            token = generation
        }
        val jpeg = try {
            // OCR-only capture. Do not enqueue frames to the game-state
            // recognizer while a popup occludes the table.
            ByteArrayOutputStream().use { stream ->
                val scaled = if (source.width > 1080)
                    Bitmap.createScaledBitmap(source, 1080,
                        (source.height * 1080L / source.width).toInt(), true)
                    else source
                try {
                    check(scaled.compress(Bitmap.CompressFormat.JPEG, 83, stream))
                    stream.toByteArray()
                } finally {
                    if (scaled !== source) scaled.recycle()
                }
            }
        } catch (_: Exception) {
            synchronized(this) { if (generation == token) inFlight = false }
            return
        }
        OnDeviceTextRecognition.inspectStructured(jpeg) { result ->
            val finishedAt = SystemClock.elapsedRealtime()
            synchronized(this) {
                if (generation != token) return@inspectStructured
                inFlight = false
                if (OperatorStopStore.isStopped(context) || !stillSamePanel() ||
                    finishedAt < nowMs || finishedAt - nowMs > MAX_AGE_MS) {
                    previous = null
                    confirmed = null
                    return@inspectStructured
                }
                val values = result.getOrNull()?.let { OpponentPopupStatsParser.parse(it.lines) }
                    ?.takeIf { it.size >= 2 } ?: run {
                    previous = null
                    confirmed = null
                    return@inspectStructured
                }
                val candidate = PanelCandidate(
                    requireNotNull(origin.observedPackage), origin.captureEpoch,
                    requireNotNull(origin.windowGeneration), origin.localFrameSequence,
                    nowMs, values, result.getOrNull()?.let {
                        OpponentPopupStatsParser.displayedNickname(it.lines)
                    }
                )
                val prior = previous
                confirmed = if (prior != null && prior.captureEpoch == candidate.captureEpoch &&
                    prior.windowGeneration == candidate.windowGeneration &&
                    prior.sourcePackage == candidate.sourcePackage &&
                    prior.frameSeq < candidate.frameSeq &&
                    candidate.capturedAtMs - prior.capturedAtMs in 200L..5_000L &&
                    prior.values == candidate.values &&
                    prior.nickname == candidate.nickname
                ) OpponentPopupStatsSnapshot(candidate.sourcePackage, candidate.captureEpoch,
                    candidate.windowGeneration, prior.capturedAtMs,
                    candidate.capturedAtMs, candidate.values, candidate.nickname)
                else null
                previous = candidate
            }
        }
    }
}
