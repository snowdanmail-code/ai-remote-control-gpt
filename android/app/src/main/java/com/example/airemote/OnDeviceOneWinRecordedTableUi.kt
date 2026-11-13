package com.example.airemote

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import java.io.ByteArrayOutputStream

data class OneWinRecordedUiSnapshot(
    val sourcePackage: String,
    val captureEpoch: Long,
    val windowGeneration: Long,
    val frameSequence: Long,
    val confirmedAtMs: Long,
    val reading: OneWinRecordedTableUiReading
)

/**
 * Local rus+eng OCR diagnostic for the actual 1win table, independent of
 * the Latin-only card/action OCR pipeline. It NEVER produces gestures.
 * The same native Tesseract worker is shared with lobby/exit diagnostics,
 * so the work is bounded to a narrow lower-screen crop and one
 * scan approximately every 1.2 seconds when the OCR worker is free.
 */
object OnDeviceOneWinRecordedTableUi {
    private const val INTERVAL_MS = 1_200L
    private const val MAX_AGE_MS = 6_000L
    private var generation = 0L
    private var lastStarted = -INTERVAL_MS
    private var busy = false
    private val detector = OneWinRecordedTableUiConsistency()
    @Volatile private var latestResult: OneWinRecordedUiSnapshot? = null
    @Volatile var lastStatus = "Видео-интерфейс 1win ещё не распознавался"
        private set

    @Synchronized fun current(nowMs: Long): OneWinRecordedUiSnapshot? =
        latestResult?.takeIf { nowMs >= it.confirmedAtMs &&
            nowMs - it.confirmedAtMs <= MAX_AGE_MS }

    @Synchronized fun reset() {
        generation++
        busy = false
        detector.reset()
        lastStarted = -INTERVAL_MS
        latestResult = null
        lastStatus = "Сеанс экранного распознавания сброшен"
    }

    fun offer(context: Context, bitmap: Bitmap,
              origin: WindowOriginAssessment, atMs: Long,
              rootStillSame: () -> Boolean) {
        if (OperatorStopStore.isStopped(context) ||
            CaptureObservationMode.isSpectator() ||
            OnDeviceOneWinTableAutoExit.needsScreenProbe() ||
            OnDeviceCashLobbySelection.scanning() ||
            !origin.safeForLocalDiagnostics ||
            origin.observedPackage.isNullOrBlank() ||
            origin.windowGeneration == null) return
        val token: Long
        synchronized(this) {
            if (busy || atMs - lastStarted < INTERVAL_MS) return
            token = generation
            busy = true
            lastStarted = atMs
        }
        // Bet sizing is transient. OCR only the lower 56% of the screen:
        // includes the vertical sizing slider and all bottom buttons, but
        // avoids decoding avatar names, board/card art and top status bar.
        // Convert OCR rectangles back to full-screen [0,1000] coordinates.
        val cropTop = (bitmap.height * 44 / 100).coerceAtLeast(0)
        val cropHeight = bitmap.height - cropTop
        val jpeg = try {
            val crop = Bitmap.createBitmap(bitmap, 0, cropTop,
                bitmap.width, cropHeight)
            try {
                ByteArrayOutputStream().use { out ->
                    check(crop.compress(Bitmap.CompressFormat.JPEG, 86, out))
                    out.toByteArray()
                }
            } finally {
                if (crop !== bitmap) crop.recycle()
            }
        } catch (_: Exception) {
            synchronized(this) {
                if (token == generation) {
                    busy = false
                    lastStatus = "Не удалось подготовить кадр; следующий будет обработан"
                }
            }
            return
        }
        OnDeviceCashLobbyTextRecognition.inspectStructured(context, jpeg) { outcome ->
            val now = SystemClock.elapsedRealtime()
            synchronized(this) {
                if (token != generation) return@inspectStructured
                busy = false
                if (OperatorStopStore.isStopped(context) || !FrameStore.isCapturing ||
                    !rootStillSame() || now < atMs || now - atMs > MAX_AGE_MS) {
                    latestResult = null
                    detector.reset()
                    return@inspectStructured
                }
                val lines = outcome.getOrNull()?.lines
                if (lines == null) {
                    latestResult = null
                    lastStatus = "Русский OCR не прочитал таблицу; повтор по следующему кадру"
                    return@inspectStructured
                }
                val normalized = lines.map { line ->
                    fun absoluteY(cropY: Int): Int =
                        ((cropTop.toLong() * 1000L +
                            cropY.toLong() * cropHeight) / bitmap.height).toInt()
                    line.copy(box = NormalizedTextBox(line.box.left,
                        absoluteY(line.box.top), line.box.right,
                        absoluteY(line.box.bottom)))
                }
                val proof = OcrFrameTicket(origin.captureEpoch,
                    origin.localFrameSequence, atMs,
                    requireNotNull(origin.observedPackage),
                    requireNotNull(origin.windowGeneration))
                val reading = detector.observe(
                    proof, OneWinRecordedCashTableUi.read(normalized))
                latestResult = if (reading.panel != OneWinRecordedTablePanel.UNKNOWN)
                    OneWinRecordedUiSnapshot(proof.sourcePackage, proof.captureEpoch,
                        proof.windowGeneration, proof.frameSequence, now, reading)
                else null
                lastStatus = if (latestResult == null)
                    "Ожидаем повторное совпадение: " + reading.evidence
                else "Подтверждена экранная панель " + reading.panel +
                    (reading.proposedStakeCents?.let {
                        "; предложено " + (it / 100) + "." +
                            (it % 100).toString().padStart(2, '0') + " $"
                    } ?: "") + ". Только диагностика, без касаний."
            }
        }
    }
}
