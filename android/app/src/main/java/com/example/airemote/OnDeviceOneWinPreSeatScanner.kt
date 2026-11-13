package com.example.airemote

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import java.io.ByteArrayOutputStream

enum class OneWinPreSeatPhase {
    OFF, SEARCHING, VACANCIES_CONFIRMED, SEAT_TAP_PENDING,
    WAITING_BUYIN_AFTER_TAP, WAITING_BUYIN,
    BUYIN_RECHECK, BUYIN_VERIFIED, MIN_TAP_PENDING, WAITING_MIN_RECHECK,
    OK_TAP_PENDING, WAITING_SEATED_AFTER_OK, VERIFYING_SEATED, SEATED,
    CANCELLED, BLOCKED
}
data class OneWinPreSeatDiagnostic(
    val phase: OneWinPreSeatPhase,
    val description: String,
    val vacancies: List<OneWinVacantSeatMarker> = emptyList(),
    val buyIn: OneWinBuyInDialogReading? = null,
    val withinConfiguredLimits: Boolean = false,
    val selectedSlot: OneWinPreSeatSlot? = null,
    val gesture: OneWinSeatEntryGestureOutcome =
        OneWinSeatEntryGestureOutcome.NEVER_DISPATCHED
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
    private var armSeatEntry = false
    private var autoConfirmMinimum = false
    private var minimumTapAttempted = false
    private var minimumTapCompletedAtMs = -1L
    private var okTapAttempted = false
    private var okTapCompleted = false
    private var pendingBuyInTap: OneWinBuyInTapTicket? = null
    private var lastBuyInTouchMs = -1L
    private var seatTapAttempted = false
    private var requestCounter = 0L
    private var pendingSeatTap: OneWinVacantSeatTapTicket? = null
    private var lastSeatTapObservationAtMs = 0L
    private var buyInNoTextFrames = 0
    private var buyInVerifiedOnce = false
    private var lastVerifiedBuyIn: OneWinBuyInDialogReading? = null
    private val postBuyInHero = OneWinBottomHeroAfterBuyInAgreement()
    @Volatile private var status = OneWinPreSeatDiagnostic(
        OneWinPreSeatPhase.OFF, "Диагностика посадки выключена")

    @Synchronized fun current(): OneWinPreSeatDiagnostic = status
    @Synchronized fun active(): Boolean =
        status.phase !in setOf(OneWinPreSeatPhase.OFF, OneWinPreSeatPhase.CANCELLED,
            OneWinPreSeatPhase.BLOCKED, OneWinPreSeatPhase.SEATED)

    @Synchronized fun stop(reason: String = "Диагностика остановлена") {
        session++
        observedPackage = ""
        epoch = -1L
        inFlight = false
        lastRun = -INTERVAL_MS
        knownVacancies = emptyList()
        armSeatEntry = false
        autoConfirmMinimum = false
        minimumTapAttempted = false
        minimumTapCompletedAtMs = -1L
        okTapAttempted = false
        okTapCompleted = false
        pendingBuyInTap = null
        pendingBuyInContext = null
        lastBuyInTouchMs = -1L
        seatTapAttempted = false
        pendingSeatTap = null
        lastSeatTapObservationAtMs = 0L
        buyInNoTextFrames = 0
        buyInVerifiedOnce = false
        lastVerifiedBuyIn = null
        postBuyInHero.reset()
        requestCounter++
        arrowAgreement.reset()
        buyInAgreement.reset()
        status = OneWinPreSeatDiagnostic(OneWinPreSeatPhase.OFF, reason)
    }

    @Synchronized fun start(context: Context, chooseSeat: Boolean = false): Boolean {
        if (OperatorStopStore.isStopped(context) || !FrameStore.isCapturing ||
            CaptureObservationMode.isSpectator() ||
            !LiveGamePermissionGate.permitted() ||
            OnDeviceOneWinTableAutoExit.active() ||
            LocalPlayerCheckPilot.isActive() ||
            OnDeviceLiveActionControl.guard.hasUnconfirmedAction() ||
            OnDeviceAutonomousRuntime.status().state == AutoLoopState.WAITING_FOR_RESULT ||
            LiveGamePermissionGate.packageName().isNullOrBlank()) return false
        stop("Новый просмотр посадки")
        observedPackage = requireNotNull(LiveGamePermissionGate.packageName())
        epoch = FrameStore.captureEpoch
        armSeatEntry = chooseSeat
        autoConfirmMinimum = chooseSeat
        status = OneWinPreSeatDiagnostic(OneWinPreSeatPhase.SEARCHING,
            "Ищем стрелки свободных мест по изображению и окно «Бай-Ин» по OCR")
        return true
    }

    /** OCR failures keep observing; a missing Android gesture callback does NOT. */
    @Synchronized fun onCaptureTick(context: Context, nowMs: Long) {
        if (!active()) return
        if (!FrameStore.isCapturing || OperatorStopStore.isStopped(context) ||
            !LiveGamePermissionGate.permitted() ||
            FrameStore.captureEpoch != epoch ||
            LiveGamePermissionGate.packageName() != observedPackage) {
            stop("Захват/разрешение изменены; посадка сброшена")
            return
        }
        if (status.phase in setOf(OneWinPreSeatPhase.MIN_TAP_PENDING,
                OneWinPreSeatPhase.OK_TAP_PENDING) &&
            pendingBuyInTap != null && nowMs >= lastBuyInTouchMs &&
            nowMs - lastBuyInTouchMs > 12_000L) {
            pendingBuyInTap = null
            status = status.copy(phase = OneWinPreSeatPhase.BLOCKED,
                description = "Не получено подтверждение жеста бай-ина от Android; " +
                    "касание больше не повторяем")
        }
        if (status.phase == OneWinPreSeatPhase.SEAT_TAP_PENDING &&
            pendingSeatTap != null &&
            nowMs >= lastSeatTapObservationAtMs &&
            nowMs - lastSeatTapObservationAtMs > 12_000L) {
            pendingSeatTap = null
            status = status.copy(phase = OneWinPreSeatPhase.BLOCKED,
                description = "Android не подтвердил касание за 12 секунд; " +
                    "повторного нажатия не выполняем",
                gesture = OneWinSeatEntryGestureOutcome.REJECTED_BY_ANDROID)
        }
    }

    /**
     * Operator explicitly arms ONE non-money seat-arrow tap. It can also be
     * armed when the user starts the existing automatic lobby-table search.
     */
    @Synchronized fun armSeatNavigation(context: Context): Boolean {
        if (status.phase == OneWinPreSeatPhase.SEATED) return false
        if (!active()) return start(context, chooseSeat = true)
        if (seatTapAttempted || pendingSeatTap != null ||
            status.phase !in setOf(OneWinPreSeatPhase.SEARCHING,
                OneWinPreSeatPhase.VACANCIES_CONFIRMED) ||
            OperatorStopStore.isStopped(context) ||
            !FrameStore.isCapturing || CaptureObservationMode.isSpectator() ||
            !LiveGamePermissionGate.permitted()) return false
        armSeatEntry = true
        autoConfirmMinimum = true
        status = status.copy(description =
            "Однократный выбор стрелки включён; ожидаем два новых совпадающих кадра")
        arrowAgreement.reset()
        knownVacancies = emptyList()
        return true
    }

    /**
     * One-step legacy-safe opt-in for a manually opened dialog; no seat
     * gesture required. Only the current active session and previously
     * unconfirmed buy-in qualify; no repeat OK after a submitted click.
     */
    @Synchronized fun armMinimumBuyIn(context: Context): Boolean {
        if (!active() || okTapAttempted || pendingBuyInTap != null ||
            OperatorStopStore.isStopped(context) || !FrameStore.isCapturing ||
            CaptureObservationMode.isSpectator() ||
            !LiveGamePermissionGate.permitted() ||
            status.phase !in setOf(OneWinPreSeatPhase.WAITING_BUYIN,
                OneWinPreSeatPhase.BUYIN_RECHECK,
                OneWinPreSeatPhase.BUYIN_VERIFIED,
                OneWinPreSeatPhase.WAITING_MIN_RECHECK)) return false
        autoConfirmMinimum = true
        buyInAgreement.reset()
        status = status.copy(description = "Авто-минимум включён; " +
            "ждём два новых совпадающих чтения суммы перед кнопкой «ОК»")
        return true
    }

    @Synchronized fun buyInTicketMatches(ticket: OneWinBuyInTapTicket,
                                          nowMs: Long): Boolean {
        if (pendingBuyInTap != ticket ||
            ticket.sourcePackage != observedPackage ||
            ticket.captureEpoch != epoch ||
            ticket.captureEpoch != FrameStore.captureEpoch ||
            ticket.windowGeneration != FrameStore.windowOrigin?.windowGeneration ||
            !FrameStore.isCapturing ||
            nowMs < ticket.observedAtMs ||
            nowMs - ticket.observedAtMs > 1_100L ||
            ticket.observedAtMs < ticket.capturedAtMs ||
            ticket.observedAtMs - ticket.capturedAtMs > 4_000L) return false
        val limits = OneWinCashLobbyLimitsStore.read(
            pendingBuyInContext ?: return false)
        val decision = OneWinMinimumBuyInPolicy.evaluate(ticket.expected, limits,
            ticket.purpose == OneWinBuyInTapPurpose.CONFIRM_OK &&
                minimumTapAttempted)
        return when (ticket.purpose) {
            OneWinBuyInTapPurpose.CHOOSE_MIN ->
                status.phase == OneWinPreSeatPhase.MIN_TAP_PENDING &&
                    minimumTapAttempted &&
                    ticket.expected.minimumCents == ticket.minimumCents &&
                    ticket.target == ticket.expected.minButtonBox &&
                    decision.step == OneWinMinBuyInStep.PRESS_MIN
            OneWinBuyInTapPurpose.CONFIRM_OK ->
                status.phase == OneWinPreSeatPhase.OK_TAP_PENDING &&
                    okTapAttempted &&
                    ticket.target == ticket.expected.okBox &&
                    ticket.expected.inputCents == ticket.minimumCents &&
                    ticket.expected.debitCents == ticket.minimumCents &&
                    decision.step == OneWinMinBuyInStep.PRESS_OK
        }
    }

    private var pendingBuyInContext: Context? = null

    private fun submitBuyInTap(context: Context, ticket: OneWinBuyInTapTicket) {
        val service = LiveForegroundProbe.activeService()
        if (service == null) {
            pendingBuyInTap = null
            status = status.copy(phase = OneWinPreSeatPhase.BLOCKED,
                description = "Accessibility не подключён; бай-ин не подтверждён")
            return
        }
        pendingBuyInContext = context.applicationContext
        service.offerOneWinBuyInNavigation(ticket) { completed ->
            synchronized(this) {
                if (pendingBuyInTap != ticket) return@synchronized
                pendingBuyInTap = null
                if (!completed) {
                    status = status.copy(
                        phase = OneWinPreSeatPhase.BLOCKED,
                        description = "Жест бай-ина отменён Android; нет повтора")
                    return@synchronized
                }
                if (ticket.purpose == OneWinBuyInTapPurpose.CHOOSE_MIN) {
                    minimumTapCompletedAtMs = android.os.SystemClock.elapsedRealtime()
                    buyInAgreement.reset()
                    if (status.phase == OneWinPreSeatPhase.MIN_TAP_PENDING)
                        status = status.copy(phase = OneWinPreSeatPhase.WAITING_MIN_RECHECK,
                            description = "Нажали «Мин»; проверяем новое значение и списание")
                } else {
                    okTapCompleted = true
                    if (status.phase == OneWinPreSeatPhase.OK_TAP_PENDING)
                        status = status.copy(phase = OneWinPreSeatPhase.WAITING_SEATED_AFTER_OK,
                            description = "Android завершил нажатие «ОК»; " +
                                "ожидаем две проверки посадки HERO")
                }
            }
        }
    }

    private fun dispatchMinimumBuyInDecision(
        context: Context, nowMs: Long,
        ticket: OcrFrameTicket, frameWidth: Int, frameHeight: Int,
        approved: OneWinBuyInDialogReading, decision: OneWinMinBuyInDecision
    ) {
        val target = decision.target ?: return
        val amount = decision.amountCents ?: return
        if (!autoConfirmMinimum || pendingBuyInTap != null || okTapAttempted ||
            status.phase == OneWinPreSeatPhase.BLOCKED) return
        if (decision.step == OneWinMinBuyInStep.PRESS_MIN && minimumTapAttempted)
            return
        if (decision.step !in setOf(OneWinMinBuyInStep.PRESS_MIN,
                OneWinMinBuyInStep.PRESS_OK)) return
        requestCounter++
        val purpose = if (decision.step == OneWinMinBuyInStep.PRESS_MIN)
            OneWinBuyInTapPurpose.CHOOSE_MIN else OneWinBuyInTapPurpose.CONFIRM_OK
        val click = OneWinBuyInTapTicket(requestCounter, purpose,
            ticket.sourcePackage, ticket.captureEpoch, ticket.windowGeneration,
            ticket.frameSequence, ticket.capturedAtMs, frameWidth,
            frameHeight, amount, target, approved)
        pendingBuyInTap = click
        lastBuyInTouchMs = nowMs
        if (purpose == OneWinBuyInTapPurpose.CHOOSE_MIN) {
            minimumTapAttempted = true
            status = status.copy(phase = OneWinPreSeatPhase.MIN_TAP_PENDING,
                description = "Выбираем минимальный бай-ин " + (amount / 100.0))
        } else {
            okTapAttempted = true
            status = status.copy(phase = OneWinPreSeatPhase.OK_TAP_PENDING,
                description = "Однократно подтверждаем минимальный бай-ин " +
                    (amount / 100.0))
        }
        submitBuyInTap(context, click)
    }

    @Synchronized fun seatTicketMatches(ticket: OneWinVacantSeatTapTicket,
                                        nowMs: Long): Boolean =
        pendingSeatTap == ticket && seatTapAttempted &&
            status.phase == OneWinPreSeatPhase.SEAT_TAP_PENDING &&
            ticket.sourcePackage == observedPackage &&
            ticket.captureEpoch == epoch && ticket.captureEpoch == FrameStore.captureEpoch &&
            ticket.windowGeneration == FrameStore.windowOrigin?.windowGeneration &&
            ticket.selected.slot == OneWinVacantSeatGeometry.slotFor(ticket.selected.marker) &&
            nowMs >= ticket.observedAtMs &&
            nowMs - ticket.observedAtMs <= 1_100L

    private fun sendSeatTap(context: Context, ticket: OneWinVacantSeatTapTicket) {
        val service = LiveForegroundProbe.activeService()
        if (service == null) {
            pendingSeatTap = null
            status = status.copy(phase = OneWinPreSeatPhase.BLOCKED,
                description = "Нет службы Android Accessibility, касаний не было",
                gesture = OneWinSeatEntryGestureOutcome.REJECTED_BY_ANDROID)
            return
        }
        service.offerOneWinVacantSeatEntry(ticket) { submitted ->
            synchronized(this) {
                if (pendingSeatTap != ticket) return@synchronized
                pendingSeatTap = null
                if (submitted) {
                    // OCR may see the modal before Android delivers the
                    // gesture-completion callback. NEVER regress an already
                    // confirmed buy-in or seated state back to WAITING.
                    if (status.phase == OneWinPreSeatPhase.SEAT_TAP_PENDING)
                        status = status.copy(
                            phase = OneWinPreSeatPhase.WAITING_BUYIN_AFTER_TAP,
                            description = "Касание свободного места выполнено; " +
                                "ищем «Бай-Ин» на новых кадрах",
                            gesture = OneWinSeatEntryGestureOutcome.ACCEPTED_BY_ANDROID)
                    else status = status.copy(
                        gesture = OneWinSeatEntryGestureOutcome.ACCEPTED_BY_ANDROID)
                } else status = status.copy(
                    phase = OneWinPreSeatPhase.BLOCKED,
                    description = "Android отклонил касание; " +
                        "повторная отправка запрещена",
                    gesture = OneWinSeatEntryGestureOutcome.REJECTED_BY_ANDROID)
            }
        }
    }

    fun offer(context: Context, frame: Bitmap, origin: WindowOriginAssessment,
              capturedAt: Long, tableLike: Boolean,
              rootStillSame: () -> Boolean) {
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
        val originalFrameWidth = frame.width
        val originalFrameHeight = frame.height
        // Tie lower-hole-card proof to THIS captured frame before the
        // asynchronous Cyrillic OCR worker starts. Reading FrameStore
        // inside a delayed OCR callback would mix different frames.
        val cardAtCapture = FrameStore.confirmedCards?.takeIf {
            it.captureEpoch == ticket.captureEpoch &&
                ticket.capturedAtMs >= it.capturedAtMs &&
                ticket.capturedAtMs - it.capturedAtMs <= 1_500L
        }
        // The pixels are copied on the capture worker before its Bitmap is recycled.
        var scaled: Bitmap? = null
        val evidence = runCatching {
            val targetH = (frame.height.toLong() * 354 / frame.width).toInt()
            scaled = Bitmap.createScaledBitmap(frame, 354, targetH, true)
            val pixels = IntArray(354 * targetH)
            requireNotNull(scaled).getPixels(pixels, 0, 354, 0, 0, 354, targetH)
            val candidates = if (tableLike)
                OneWinFreeSeatArrowDetector.detect(pixels, 354, targetH)
                    .filter { OneWinVacantSeatGeometry.slotFor(it) != null }
                else emptyList()
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

        // Only a verified table-like surface can contribute a seat vote.
        // Two distinct frames must agree; no BUY-IN modal or lobby counts.
        synchronized(this) {
            if (generation == session && !OperatorStopStore.isStopped(context) &&
                status.phase in setOf(OneWinPreSeatPhase.SEARCHING,
                    OneWinPreSeatPhase.VACANCIES_CONFIRMED)) {
                val stable = arrowAgreement.observe(ticket, evidence.first)
                val choice = OneWinVacantSeatGeometry.choose(stable)
                if (choice != null) {
                    knownVacancies = stable
                    status = OneWinPreSeatDiagnostic(
                        OneWinPreSeatPhase.VACANCIES_CONFIRMED,
                        "Подтверждено свободных мест: " + stable.size +
                            if (armSeatEntry) "; выбрано место " + choice.slot
                                else "; ожидаем выбор оператора",
                        stable, selectedSlot = choice.slot)
                    if (armSeatEntry && !seatTapAttempted &&
                        !OnDeviceLiveActionControl.guard.hasUnconfirmedAction() &&
                        !LocalPlayerCheckPilot.isActive() &&
                        OnDeviceAutonomousRuntime.status().state !=
                            AutoLoopState.WAITING_FOR_RESULT) {
                        requestCounter++
                        val click = OneWinVacantSeatTapTicket(
                            requestCounter, observedPackage, epoch,
                            requireNotNull(origin.windowGeneration),
                            ticket.frameSequence, ticket.capturedAtMs,
                            frame.width, frame.height, choice)
                        seatTapAttempted = true
                        pendingSeatTap = click
                        lastSeatTapObservationAtMs = capturedAt
                        status = status.copy(
                            phase = OneWinPreSeatPhase.SEAT_TAP_PENDING,
                            description = "Выбрана проверенная стрелка " +
                                choice.slot + "; касание однократное",
                            gesture = OneWinSeatEntryGestureOutcome.REQUESTED)
                        sendSeatTap(context, click)
                    }
                } else {
                    knownVacancies = emptyList()
                    if (status.phase != OneWinPreSeatPhase.VACANCIES_CONFIRMED ||
                        evidence.first.isEmpty()) {
                        status = OneWinPreSeatDiagnostic(OneWinPreSeatPhase.SEARCHING,
                            "Стрелки не подтверждены двумя кадрами; продолжаем чтение")
                    }
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
                    // A missing OCR result is not proof that the buy-in
                    // dialog closed, nor evidence of a seated hero.
                    postBuyInHero.reset()
                    status = status.copy(description =
                        "OCR не распознал окно, повторно проверяем экран без нажатий")
                    return@inspectStructured
                }
                val reading = OneWinBuyInDialogParser.read(lines)
                if (reading.status == OneWinBuyInReadingStatus.NOT_DIALOG) {
                    buyInAgreement.reset()
                    if (buyInVerifiedOnce &&
                        (!autoConfirmMinimum || (okTapAttempted && okTapCompleted))) {
                        // A completed minimum buy-in must now be followed
                        // by current, independently recognized bottom cards.
                        // Closing with X will never satisfy this requirement.
                        val cards = cardAtCapture
                        val evidence = cards?.let {
                            OneWinHeroAfterBuyInFrame(
                                ticket.sourcePackage, ticket.captureEpoch,
                                ticket.windowGeneration, ticket.frameSequence,
                                ticket.capturedAtMs, it.captureEpoch,
                                it.capturedAtMs, it.hole)
                        }
                        val seated = postBuyInHero.observe(evidence, now,
                            buyInPreviouslyVerified = true,
                            dialogStillVisible = false)
                        status = status.copy(
                            phase = if (seated) OneWinPreSeatPhase.SEATED
                                else OneWinPreSeatPhase.VERIFYING_SEATED,
                            description = if (seated)
                                "После подтверждённого бай-ина найдены две " +
                                    "последовательные пары своих карт снизу. HERO за столом."
                            else "Окно бай-ина закрыто; ожидаем подтверждение " +
                                "двух нижних карт на новых кадрах. Посадка ещё не доказана.",
                            vacancies = emptyList(),
                            buyIn = lastVerifiedBuyIn)
                    } else if (status.phase in setOf(
                            OneWinPreSeatPhase.WAITING_BUYIN_AFTER_TAP,
                            OneWinPreSeatPhase.WAITING_BUYIN,
                            OneWinPreSeatPhase.BUYIN_RECHECK)) {
                        buyInNoTextFrames++
                        status = status.copy(
                            description = "Бай-ин ещё не виден; повторно читаем экран, " +
                                "проверка №" + buyInNoTextFrames +
                                "; место повторно не нажимаем")
                    }
                    return@inspectStructured
                }
                if (reading.status == OneWinBuyInReadingStatus.INCOMPLETE) {
                    buyInAgreement.reset()
                    postBuyInHero.reset()
                    buyInVerifiedOnce = false
                    lastVerifiedBuyIn = null
                    status = status.copy(
                        phase = OneWinPreSeatPhase.BUYIN_RECHECK,
                        description = "Окно бай-ина не полностью распознано, ждём новый кадр",
                        buyIn = reading, withinConfiguredLimits = false)
                    return@inspectStructured
                }
                // An OCR worker may return a frame acquired before the
                // Min tap completed. Those pixels are never eligible to
                // authorize the next OK tap, even if text accidentally agrees.
                if (minimumTapCompletedAtMs >= 0L &&
                    ticket.capturedAtMs <= minimumTapCompletedAtMs) {
                    buyInAgreement.reset()
                    status = status.copy(
                        phase = OneWinPreSeatPhase.WAITING_MIN_RECHECK,
                        description = "Проверяем «Мин» только на новых кадрах")
                    return@inspectStructured
                }
                // Never dispatch a second gesture from a still-pending one.
                if (pendingBuyInTap != null) {
                    status = status.copy(description =
                        "Ожидается подтверждение жеста Android; не нажимаем повторно")
                    return@inspectStructured
                }
                val agreed = buyInAgreement.observe(ticket, reading)
                val limits = OneWinCashLobbyLimitsStore.read(context)
                if (agreed != null) {
                    buyInVerifiedOnce = agreed.withinLimits(limits)
                    lastVerifiedBuyIn = if (buyInVerifiedOnce) agreed else null
                    postBuyInHero.reset()
                }
                val previous = status
                status = if (agreed != null)
                    OneWinPreSeatDiagnostic(OneWinPreSeatPhase.BUYIN_VERIFIED,
                        if (agreed.withinLimits(limits))
                            "Бай-ин распознан и укладывается в сохранённые лимиты. " +
                                "Требуется отдельное подтверждение оператора; ОК не нажимали"
                        else "Бай-ин распознан, но не подтверждён лимитами. ОК запрещён",
                        knownVacancies, agreed, agreed.withinLimits(limits),
                        previous.selectedSlot, previous.gesture)
                else OneWinPreSeatDiagnostic(
                    if (minimumTapAttempted) OneWinPreSeatPhase.WAITING_MIN_RECHECK
                        else OneWinPreSeatPhase.WAITING_BUYIN,
                    "Первое чтение бай-ина, ожидаем второй независимый кадр",
                    knownVacancies, reading, false,
                    previous.selectedSlot, previous.gesture)

                // Minimal buy-in, NEVER "max" or an automatic rebuy.
                // Two identical OCR frames are required before each touch.
                if (agreed != null && autoConfirmMinimum && !okTapAttempted &&
                    !OperatorStopStore.isStopped(context) &&
                    !CaptureObservationMode.isSpectator() &&
                    LiveGamePermissionGate.permitted() &&
                    FrameStore.captureEpoch == ticket.captureEpoch &&
                    OnDeviceAutonomousRuntime.status().state !=
                        AutoLoopState.WAITING_FOR_RESULT &&
                    !OnDeviceLiveActionControl.guard.hasUnconfirmedAction() &&
                    !LocalPlayerCheckPilot.isActive()) {
                    val decision = OneWinMinimumBuyInPolicy.evaluate(
                        agreed, limits, minimumTapAttempted)
                    when (decision.step) {
                        OneWinMinBuyInStep.PRESS_MIN, OneWinMinBuyInStep.PRESS_OK ->
                            dispatchMinimumBuyInDecision(context, now, ticket,
                                originalFrameWidth, originalFrameHeight,
                                agreed, decision)
                        OneWinMinBuyInStep.RECHECK_OCR ->
                            status = status.copy(description =
                                "Не подтверждён минимальный бай-ин: " +
                                    decision.explanation + "; повторно читаем окно")
                    }
                }
            }
        }
    }
}
