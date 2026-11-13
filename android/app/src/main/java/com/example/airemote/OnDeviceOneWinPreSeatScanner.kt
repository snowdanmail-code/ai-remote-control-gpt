package com.example.airemote

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import java.io.ByteArrayOutputStream

enum class OneWinPreSeatPhase {
    OFF, SEARCHING, VACANCIES_CONFIRMED, WAITING_BUYIN,
    BUYIN_RECHECK, BUYIN_VERIFIED, CANCELLED
}
data class OneWinPreSeatDiagnostic(
    val phase: OneWinPreSeatPhase,
    val description: String,
    val vacancies: List<OneWinVacantSeatMarker> = emptyList(),
    val buyIn: OneWinBuyInDialogReading? = null,
    val withinConfiguredLimits: Boolean = false
)

/**
 * User's 1win seating screenshots, local read-only observation.
 * No automatic seat tapping, buy-in confirmation, balance transfer or gameplay.
 * Screens of other applications, STOP and an invalid capture reset evidence.
 */
object OnDeviceOneWinPreSeatScanner {
    private const val INTERVAL_MS = 1_500L
    private var session = 0L
    private var observedPackage = ""
    private var epoch = -1L
    private var inFlight = false
    private var lastRun = -INTERVAL_MS
    private val arrowAgreement = OneWinFreeSeatAgreement()
    private val buyInAgreement = OneWinBuyInAgreement()
    private var knownVacancies = emptyList<OneWinVacantSeatMarker>()
    @Volatile private var status = OneWinPreSeatDiagnostic(
        OneWinPreSeatPhase.OFF, "Диагностика посадки выключена")

    @Synchronized fun current(): OneWinPreSeatDiagnostic = status
    @Synchronized fun active(): Boolean =
        status.phase !in setOf(OneWinPreSeatPhase.OFF, OneWinPreSeatPhase.CANCELLED)

    @Synchronized fun stop(reason: String = "Диагностика остановлена") {
        session++
        observedPackage = ""
        epoch = -1L
        inFlight = false
        lastRun = -INTERVAL_MS
        knownVacancies = emptyList()
        arrowAgreement.reset()
        buyInAgreement.reset()
        status = OneWinPreSeatDiagnostic(OneWinPreSeatPhase.OFF, reason)
    }

    @Synchronized fun start(context: Context): Boolean {
        if (OperatorStopStore.isStopped(context) || !FrameStore.isCapturing ||
            CaptureObservationMode.isSpectator() ||
            !LiveGamePermissionGate.permitted() ||
            LiveGamePermissionGate.packageName().isNullOrBlank()) return false
        stop("Новый просмотр посадки")
        observedPackage = requireNotNull(LiveGamePermissionGate.packageName())
        epoch = FrameStore.captureEpoch
        status = OneWinPreSeatDiagnostic(OneWinPreSeatPhase.SEARCHING,
            "Ищем стрелки свободных мест по изображению и окно «Бай-Ин» по OCR")
        return true
    }

    fun offer(context: Context, frame: Bitmap, origin: WindowOriginAssessment,
              capturedAt: Long, rootStillSame: () -> Boolean) {
        val ticket: OcrFrameTicket
        val generation: Long
        synchronized(this) {
            if (!active() || inFlight || capturedAt - lastRun < INTERVAL_MS ||
                OperatorStopStore.isStopped(context) ||
                !origin.safeForLocalDiagnostics ||
                origin.observedPackage != observedPackage ||
                origin.captureEpoch != epoch ||
                origin.windowGeneration == null) return
            generation = session
            inFlight = true
            lastRun = capturedAt
            ticket = OcrFrameTicket(epoch, origin.localFrameSequence,
                capturedAt, observedPackage, origin.windowGeneration)
        }
        // The pixels are copied on the capture worker before its Bitmap is recycled.
        var scaled: Bitmap? = null
        val evidence = runCatching {
            val targetH = (frame.height.toLong() * 354 / frame.width).toInt()
            scaled = Bitmap.createScaledBitmap(frame, 354, targetH, true)
            val pixels = IntArray(354 * targetH)
            requireNotNull(scaled).getPixels(pixels, 0, 354, 0, 0, 354, targetH)
            val candidates = OneWinFreeSeatArrowDetector.detect(pixels, 354, targetH)
            val jpeg = ByteArrayOutputStream().use { out ->
                check(frame.compress(Bitmap.CompressFormat.JPEG, 86, out))
                out.toByteArray()
            }
            candidates to jpeg
        }.getOrNull()
        if (scaled != null && scaled !== frame) scaled?.recycle()
        if (evidence == null) {
            synchronized(this) {
                if (generation == session) {
                    inFlight = false
                    status = status.copy(description =
                        "Ошибка чтения кадра, проверим снова без касаний")
                }
            }
            return
        }

        // Screen geometry alone is not permission for any click.
        synchronized(this) {
            if (generation == session && !OperatorStopStore.isStopped(context)) {
                val stable = arrowAgreement.observe(ticket, evidence.first)
                if (stable.isNotEmpty()) {
                    knownVacancies = stable
                    status = OneWinPreSeatDiagnostic(
                        OneWinPreSeatPhase.VACANCIES_CONFIRMED,
                        "Подтверждено свободных мест: " + stable.size +
                            "; ожидаем ручной выбор места; касаний не было",
                        stable)
                } else if (status.phase in setOf(
                        OneWinPreSeatPhase.SEARCHING,
                        OneWinPreSeatPhase.VACANCIES_CONFIRMED)) {
                    status = OneWinPreSeatDiagnostic(OneWinPreSeatPhase.SEARCHING,
                        "Проверяем расположение стрелок на следующем кадре",
                        knownVacancies)
                }
            }
        }
        OnDeviceCashLobbyTextRecognition.inspectStructured(context,
            evidence.second) { outcome ->
            val now = SystemClock.elapsedRealtime()
            synchronized(this) {
                if (generation != session) return@inspectStructured
                inFlight = false
                if (OperatorStopStore.isStopped(context) || !FrameStore.isCapturing ||
                    !rootStillSame() || now - capturedAt !in 0L..6_000L) {
                    knownVacancies = emptyList()
                    arrowAgreement.reset()
                    buyInAgreement.reset()
                    status = OneWinPreSeatDiagnostic(OneWinPreSeatPhase.SEARCHING,
                        "Окно / захват не подтверждены; требуется новый кадр")
                    return@inspectStructured
                }
                val lines = outcome.getOrNull()?.lines
                if (lines == null) {
                    status = status.copy(description =
                        "OCR не распознал окно, повторно проверяем экран без нажатий")
                    return@inspectStructured
                }
                val reading = OneWinBuyInDialogParser.read(lines)
                if (reading.status == OneWinBuyInReadingStatus.NOT_DIALOG) {
                    buyInAgreement.reset()
                    return@inspectStructured
                }
                if (reading.status == OneWinBuyInReadingStatus.INCOMPLETE) {
                    status = status.copy(
                        phase = OneWinPreSeatPhase.BUYIN_RECHECK,
                        description = "Окно бай-ина не полностью распознано, ждём новый кадр",
                        buyIn = reading, withinConfiguredLimits = false)
                    return@inspectStructured
                }
                val agreed = buyInAgreement.observe(ticket, reading)
                val limits = OneWinCashLobbyLimitsStore.read(context)
                status = if (agreed != null)
                    OneWinPreSeatDiagnostic(OneWinPreSeatPhase.BUYIN_VERIFIED,
                        if (agreed.withinLimits(limits))
                            "Бай-ин распознан и укладывается в сохранённые лимиты. " +
                                "Требуется отдельное подтверждение оператора; ОК не нажимали"
                        else "Бай-ин распознан, но не подтверждён лимитами. ОК запрещён",
                        knownVacancies, agreed, agreed.withinLimits(limits))
                else OneWinPreSeatDiagnostic(OneWinPreSeatPhase.WAITING_BUYIN,
                    "Первое чтение бай-ина, ожидаем второй независимый кадр",
                    knownVacancies, reading, false)
            }
        }
    }
}
