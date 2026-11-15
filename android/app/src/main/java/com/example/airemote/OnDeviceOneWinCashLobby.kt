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

/** Two OCR frames must agree on the exact popup labels before menu navigation. */
data class OneWinCashLobbySortMenuSnapshot(
    val sourcePackage: String,
    val captureEpoch: Long,
    val windowGeneration: Long,
    val capturedAtMs: Long,
    val confirmedAtMs: Long,
    val options: Map<OneWinLobbySort, NormalizedTextBox>
)

object OnDeviceOneWinCashLobby {
    private const val INTERVAL_MS = 1_300L
    // Full-frame multilingual Tesseract is slower than Latin-only ML Kit.
    // Navigation still separately requires a fresh capture-window proof.
    private const val MAX_AGE_MS = 6_000L
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
    private data class SortCandidate(
        val source: String, val epoch: Long, val window: Long,
        val seq: Long, val atMs: Long, val menu: OneWinLobbySortMenu
    )
    private var priorSort: SortCandidate? = null
    @Volatile private var confirmedSort: OneWinCashLobbySortMenuSnapshot? = null
    @Volatile private var result: OneWinCashLobbySnapshot? = null
    @Volatile private var parseDiagnostic: String = "OCR ещё не анализировался"

    @Synchronized fun latest(): OneWinCashLobbySnapshot? = result
    fun diagnosticStatus(): String = parseDiagnostic
    @Synchronized fun latestSortMenu(): OneWinCashLobbySortMenuSnapshot? = confirmedSort

    @Synchronized fun reset() {
        generation++
        busy = false
        previous = null
        priorSort = null
        confirmedSort = null
        result = null
        lastStartedAtMs = -INTERVAL_MS
        parseDiagnostic = "Ожидаем первый OCR-кадр"
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
     * Call only for the lobby. This observer uses local Tesseract and is not
     * permitted to send a gesture, set a buy-in, or commit account funds.
     */
    fun offer(context: Context, frame: Bitmap,
              origin: WindowOriginAssessment, nowMs: Long,
              allowSelection: Boolean = true,
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
        OnDeviceCashLobbyTextRecognition.inspectStructured(context, jpeg,
            recoverLobbyButtons = true) { outcome ->
            val endMs = SystemClock.elapsedRealtime()
            var confirmedNew: OneWinCashLobbySnapshot? = null
            var confirmedMenu: OneWinCashLobbySortMenuSnapshot? = null
            synchronized(this) {
                if (currentToken != generation) return@inspectStructured
                busy = false
                if (OperatorStopStore.isStopped(context) || !rootStillSame() ||
                    endMs - nowMs !in 0..MAX_AGE_MS) {
                    previous = null
                    result = null
                    parseDiagnostic = "OCR-кадр отклонён: STOP, смена окна или устаревшие данные"
                    return@inspectStructured
                }
                val lines = outcome.getOrNull()?.lines
                if (lines == null) {
                    parseDiagnostic = "OCR не дал структурированных строк"
                }
                val menu = lines?.let(OneWinCashLobbySortParser::read)
                if (menu != null) {
                    parseDiagnostic = "Распознано меню сортировки, список столов закрыт"
                    // Open sort dropdown overlays table rows: never retain any
                    // outdated "Войти" location while sorting is visible.
                    previous = null
                    result = null
                    val next = SortCandidate(requireNotNull(origin.observedPackage),
                        origin.captureEpoch, requireNotNull(origin.windowGeneration),
                        origin.localFrameSequence, nowMs, menu)
                    val p = priorSort
                    if (p != null && p.source == next.source && p.epoch == next.epoch &&
                        p.window == next.window && p.seq < next.seq &&
                        next.atMs - p.atMs in 300L..6_000L &&
                        p.menu.options.keys == next.menu.options.keys &&
                        p.menu.options.all { (action, box) ->
                            val currentBox = next.menu.options[action]
                            currentBox != null &&
                                abs(box.centerX() - currentBox.centerX()) <= 12 &&
                                abs(box.centerY() - currentBox.centerY()) <= 12
                        }) {
                        confirmedMenu = OneWinCashLobbySortMenuSnapshot(
                            next.source, next.epoch, next.window,
                            p.atMs, endMs, next.menu.options)
                    } else confirmedMenu = null
                    confirmedSort = confirmedMenu
                    priorSort = next
                    return@synchronized
                }
                priorSort = null
                confirmedSort = null
                val scan = lines?.let(OneWinCashLobbyParser::read)
                val counters = if (lines != null && scan != null)
                    OneWinCashLobbyReadOnlyDiagnostics.summarize(lines, scan)
                else "OCR: нет строк для парсера"
                parseDiagnostic = counters
                if (scan?.headerVerified != true || scan.tables.isEmpty()) {
                    previous = null
                    result = null
                    return@synchronized
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
                parseDiagnostic = counters + "; кадры=" +
                    (if (confirmedNew != null) "ДВА СОВПАЛИ" else
                        if (p == null) "ожидаем второй" else "НЕ СОВПАЛИ") +
                    "; подтверждено=${confirmedNew?.tables?.size ?: 0}"
            }
            // T052 passive OCR must never notify any selection/navigation state machine.
            if (allowSelection) {
                confirmedMenu?.let { OnDeviceCashLobbySelection.onSortMenuConfirmed(context, it) }
                confirmedNew?.let { OnDeviceCashLobbySelection.onConfirmed(context, it) }
            }
        }
    }
}
