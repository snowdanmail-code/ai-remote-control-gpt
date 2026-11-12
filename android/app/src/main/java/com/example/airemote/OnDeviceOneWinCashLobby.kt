package com.example.airemote

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import kotlin.math.abs

/** OCR-only evidence for a 1win CASH GAMES lobby, not an authority for stakes. */
data class OneWinCashLobbySnapshot(
    val sourcePackage: String,
    val captureEpoch: Long,
    val windowGeneration: Long,
    val capturedAtMs: Long,
    val confirmedAtMs: Long,
    val tables: List<OneWinCashLobbyTable>
) {
    val authorizesBuyIn: Boolean get() = false
    val authorizesWager: Boolean get() = false
}

object OnDeviceOneWinCashLobby {
    private const val INTERVAL_MS = 1_300L
    private const val MAX_AGE_MS = 2_500L
    private data class Candidate(
        val sourcePackage: String,
        val epoch: Long,
        val generation: Long,
        val frameSeq: Long,
        val timestamp: Long,
        val tables: List<OneWinCashLobbyTable>
    )
    private var generation = 0L
    private var busy = false
    private var lastStartedAtMs = -INTERVAL_MS
    private var previous: Candidate? = null
    @Volatile private var result: OneWinCashLobbySnapshot? = null

    @Synchronized fun latest(): OneWinCashLobbySnapshot? = result

    @Synchronized fun reset() {
        generation++
        busy = false
        previous = null
        result = null
        lastStartedAtMs = -INTERVAL_MS
    }

    private fun sameTables(a: List<OneWinCashLobbyTable>,
                           b: List<OneWinCashLobbyTable>): Boolean =
        a.size == b.size && a.isNotEmpty() && a.zip(b).all { (one, two) ->
            one.label == two.label &&
                one.occupiedSeats == two.occupiedSeats &&
                one.totalSeats == two.totalSeats &&
                one.smallBlindCents == two.smallBlindCents &&
                one.bigBlindCents == two.bigBlindCents &&
                one.minBuyInCents == two.minBuyInCents &&
                one.maxBuyInCents == two.maxBuyInCents &&
                abs(one.buyInOcrBox.centerX() - two.buyInOcrBox.centerX()) <= 12 &&
                abs(one.buyInOcrBox.centerY() - two.buyInOcrBox.centerY()) <= 12
        }

    /**
     * Call ONLY for a non-playing screen. Reuses on-device ML Kit and is not
     * permitted to send a gesture, set a buy-in, or commit account funds.
     */
    fun offer(context: Context, frame: Bitmap,
              origin: WindowOriginAssessment, nowMs: Long,
              rootStillSame: () -> Boolean) {
        if (!origin.safeForLocalDiagnostics || origin.windowGeneration == null ||
            origin.observedPackage.isNullOrBlank() ||
            OperatorStopStore.isStopped(context)) return
        val currentToken: Long
        synchronized(this) {
            if (busy || nowMs - lastStartedAtMs < INTERVAL_MS) return
            busy = true
            currentToken = generation
            lastStartedAtMs = nowMs
        }
        val jpeg = try {
            ByteArrayOutputStream().use { buffer ->
                val scaled = if (frame.width > 1080)
                    Bitmap.createScaledBitmap(frame, 1080,
                        (frame.height.toLong() * 1080 / frame.width).toInt(), true)
                    else frame
                try {
                    check(scaled.compress(Bitmap.CompressFormat.JPEG, 84, buffer))
                    buffer.toByteArray()
                } finally { if (scaled !== frame) scaled.recycle() }
            }
        } catch (_: Exception) {
            synchronized(this) { if (currentToken == generation) busy = false }
            return
        }
        OnDeviceTextRecognition.inspectStructured(jpeg) { outcome ->
            val endMs = SystemClock.elapsedRealtime()
            var confirmedNew: OneWinCashLobbySnapshot? = null
            synchronized(this) {
                if (currentToken != generation) return@inspectStructured
                busy = false
                if (OperatorStopStore.isStopped(context) || !rootStillSame() ||
                    endMs - nowMs !in 0..MAX_AGE_MS) {
                    previous = null
                    result = null
                    return@inspectStructured
                }
                val scan = outcome.getOrNull()?.let { OneWinCashLobbyParser.read(it.lines) }
                if (scan?.headerVerified != true || scan.tables.isEmpty()) {
                    previous = null
                    result = null
                    return@inspectStructured
                }
                val candidate = Candidate(requireNotNull(origin.observedPackage),
                    origin.captureEpoch, requireNotNull(origin.windowGeneration),
                    origin.localFrameSequence, nowMs, scan.tables)
                val p = previous
                confirmedNew = if (p != null && p.sourcePackage == candidate.sourcePackage &&
                    p.epoch == candidate.epoch && p.generation == candidate.generation &&
                    p.frameSeq < candidate.frameSeq &&
                    candidate.timestamp - p.timestamp in 300..6_000L &&
                    sameTables(p.tables, candidate.tables)
                ) OneWinCashLobbySnapshot(candidate.sourcePackage, candidate.epoch,
                    candidate.generation, candidate.timestamp, endMs, candidate.tables)
                else null
                result = confirmedNew
                previous = candidate
            }
            confirmedNew?.let { OnDeviceCashLobbySelection.onConfirmed(context, it) }
        }
    }
}
