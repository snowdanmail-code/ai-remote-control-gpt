package com.example.airemote

/**
 * Conservative, explicitly scoped PLAYER pilot for a zero-chip CHECK only.
 *
 * This is NOT a verified hand/table identity, opponent model, betting
 * engine or a substitute for trusted provider attestation. Its one allowed
 * action is exact, enabled native Android UI CHECK (never CALL). Two
 * independent native scans + fresh on-device cards must agree.
 * No money/chip transfers, bet/raise/fold and no inference from button color.
 */
enum class LocalCheckSource { ANDROID_NATIVE, VISUAL_OCR_CALIBRATED }

data class LocalCheckTarget(
    val action: LiveAction, val label: String,
    val left: Int, val top: Int, val right: Int, val bottom: Int
)

data class LocalCheckTicket(
    val commandSequence: Long,
    val captureEpoch: Long,
    val packageName: String,
    val windowGeneration: Long,
    val cardSequence: Long,
    val hole: List<String>,
    val board: List<String>,
    val button: LocalCheckTarget,
    val source: LocalCheckSource,
    val issuedAtMs: Long
)

enum class LocalCheckState {
    OFF, WAITING_FOR_CARDS, WAITING_FOR_NATIVE_CHECK,
    VERIFYING, READY, SENT_UNCONFIRMED, WAITING_FOR_DISAPPEARANCE,
    BLOCKED
}
data class LocalCheckSnapshot(val state: LocalCheckState, val reason: String,
                              val checkCount: Long = 0)

class LocalVisualCheckGate {
    private var pending: LocalCheckTicket? = null
    private var confirmations = 0
    private var lastSeenAt = -1L
    private var awaitingDisappearance = false
    private var missingSamples = 0
    private var locked = true
    private var reason = "Выключено"
    private var count = 0L
    private var commandSeq = 0L
    private var seenSession = ""
    private var lastEpoch = -1L
    private var lastCompletedStreetKey: String? = null

    @Synchronized fun arm(): Boolean {
        if (!locked) return false
        locked = false
        pending = null
        confirmations = 0
        awaitingDisappearance = false
        missingSamples = 0
        reason = "Ждём свежие карты и независимую Android-кнопку CHECK"
        seenSession = ""
        lastEpoch = -1L
        lastCompletedStreetKey = null
        return true
    }

    @Synchronized fun stop(why: String = "STOP") {
        locked = true
        pending = null
        confirmations = 0
        awaitingDisappearance = false
        missingSamples = 0
        reason = why
    }

    @Synchronized fun status(): LocalCheckSnapshot {
        val state = when {
            locked -> LocalCheckState.OFF
            awaitingDisappearance -> LocalCheckState.SENT_UNCONFIRMED
            pending != null && confirmations >= 2 -> LocalCheckState.READY
            confirmations > 0 -> LocalCheckState.VERIFYING
            else -> LocalCheckState.WAITING_FOR_NATIVE_CHECK
        }
        return LocalCheckSnapshot(state, reason, count)
    }

    @Synchronized fun observe(
        cards: StableCardReading?,
        native: NativeControlSnapshot?,
        expectedPackage: String?,
        windowGeneration: Long,
        epoch: Long,
        nowMs: Long,
        screenWidth: Int,
        screenHeight: Int
    ): LocalCheckTicket? {
        if (locked) return null
        // Canvas-only games have no native nodes. A periodic empty native scan
        // must not erase a still-unclaimed candidate corroborated by TWO real
        // OCR JPEGs; otherwise the Android sampler races with the OCR callback.
        // Once a command is claimed, native disappearance scans still advance
        // the no-retry latch below.
        if (pending?.source == LocalCheckSource.VISUAL_OCR_CALIBRATED &&
            !awaitingDisappearance) return null
        val validNative = PlayerNativeControlEvidence.verify(native, expectedPackage,
            windowGeneration, nowMs, screenWidth, screenHeight)
        val nativeCheck = native?.buttons?.singleOrNull { it.action == LiveAction.CHECK }
        val checkOnly = nativeCheck != null && native.buttons.none { it.action == LiveAction.CALL }
        // A disabled or conflicting UI does NOT prove the previous check worked.
        // No automatic retry without clear disappearance on two independent scans.
        if (awaitingDisappearance) {
            if (!validNative || !checkOnly) {
                missingSamples++
                if (missingSamples >= 2) {
                    awaitingDisappearance = false
                    pending = null
                    confirmations = 0
                    lastSeenAt = -1
                    reason = "CHECK исчез; новый ход будет проверяться заново"
                }
            } else missingSamples = 0
            return null
        }
        val freshCards = cards != null && cards.captureEpoch == epoch &&
            cards.capturedAtMs in (nowMs - 1_200L)..nowMs &&
            cards.hole.size == 2 && cards.community.size in listOf(0,3,4,5) &&
            cards.minimumSimilarity >= 0.78
        if (!freshCards || !validNative || !checkOnly ||
            expectedPackage.isNullOrBlank() || nativeCheck == null ||
            epoch < 0L || windowGeneration < 0L) {
            confirmations = 0
            pending = null
            reason = when {
                !freshCards -> "Нет свежих двух собственных и общих карт"
                !validNative -> "Android не видит настоящие кнопки"
                else -> "Нет однозначной бесплатной кнопки CHECK"
            }
            return null
        }
        val stable = requireNotNull(cards)
        val check = requireNotNull(nativeCheck)
        val streetKey = listOf(expectedPackage, epoch.toString(),
            stable.hole.joinToString(","), stable.community.joinToString(",")).joinToString("|")
        // A native CHECK disappearing may be an animation or failed UI event,
        // not a verified game effect. Never send another CHECK on the same
        // hole+board street during this session.
        if (lastCompletedStreetKey == streetKey) {
            pending = null
            confirmations = 0
            reason = "Этот набор карт уже получил один CHECK; повтор запрещён"
            return null
        }
        val fingerprint = listOf(expectedPackage, windowGeneration.toString(),
            epoch.toString(), stable.hole.joinToString(","), stable.community.joinToString(","),
            check.left.toString(), check.top.toString(), check.right.toString(), check.bottom.toString()
        ).joinToString("|")
        if (seenSession != fingerprint || lastEpoch != epoch ||
            lastSeenAt < 0L || nowMs <= lastSeenAt ||
            nowMs - lastSeenAt > 800L) {
            seenSession = fingerprint
            lastEpoch = epoch
            confirmations = 1
            pending = null
        } else confirmations++
        lastSeenAt = nowMs
        reason = if (confirmations < 2) "Проверяем точную кнопку на втором кадре"
                 else "Бесплатный CHECK подтверждён: отдельная ограниченная ветка"
        if (confirmations < 2) return null
        if (pending != null) return null
        commandSeq++
        val ticket = LocalCheckTicket(commandSeq, epoch, expectedPackage, windowGeneration,
            stable.frameSequence, stable.hole.toList(), stable.community.toList(),
            LocalCheckTarget(check.action, check.exactLabel, check.left, check.top, check.right, check.bottom),
            LocalCheckSource.ANDROID_NATIVE, nowMs)
        pending = ticket
        return ticket
    }

    /**
     * Alternative source for Canvas-rendered CHECK buttons. It requires TWO
     * independent JPEGs (already checked by OcrDiagnosticConsensus), the
     * verified fixed 540x1170 layout, exact OCR label, visual READY state,
     * fresh separate card consensus, and a matching captured window.
     * It certifies a VISIBLE free CHECK only, not table or hand identity.
     */
    @Synchronized fun observeOcr(
        cards: StableCardReading?,
        ocr: AutomaticOcrEvidence?,
        epoch: Long, nowMs: Long,
        displayWidth: Int, displayHeight: Int
    ): LocalCheckTicket? {
        if (locked || awaitingDisappearance || pending != null ||
            cards == null || ocr == null ||
            cards.captureEpoch != epoch || ocr.ticket.captureEpoch != epoch ||
            ocr.agreement.status != OcrDiagnosticStatus.CONSISTENT_DIAGNOSTIC_ONLY ||
            ocr.turnDisplay != TurnDisplayState.READY ||
            ocr.ticket.windowGeneration < 0L ||
            ocr.ticket.sourcePackage.isBlank() ||
            ocr.ticket.capturedAtMs !in (nowMs - 1_500L)..nowMs ||
            cards.capturedAtMs !in (nowMs - 1_200L)..nowMs ||
            // The button OCR and two-frame cards MUST describe the same
            // recent game state. Never combine a next-hand card observation
            // with a stale CHECK label from a previous JPEG.
            cards.capturedAtMs !in (ocr.ticket.capturedAtMs - 650L)..(ocr.ticket.capturedAtMs + 250L) ||
            cards.frameSequence > ocr.ticket.frameSequence ||
            cards.hole.size != 2 || cards.community.size !in listOf(0,3,4,5) ||
            cards.minimumSimilarity < .78 ||
            // Require exact Android pixel mapping; no crop/rotation guessing.
            displayWidth <= 0 || displayHeight <= 0 ||
            displayWidth.toLong() * 1170 != displayHeight.toLong() * 540L)
            return null
        val actions = ocr.interpretation.actions
        if (ocr.interpretation.rejected.isNotEmpty() ||
            actions.count { it.action == ExactActionCandidate.CHECK } != 1 ||
            actions.any { it.action == ExactActionCandidate.CALL ||
                it.action == ExactActionCandidate.CONFIRM }) return null
        val check = actions.single { it.action == ExactActionCandidate.CHECK }
        if (check.box.centerX() !in 334..666 || check.box.centerY() !in 915..985)
            return null
        val streetKey = listOf(ocr.ticket.sourcePackage, epoch.toString(),
            cards.hole.joinToString(","), cards.community.joinToString(",")).joinToString("|")
        if (lastCompletedStreetKey == streetKey) return null
        // From already tested real 1win action-strip fixtures. Do NOT
        // expand OCR letter bounds or use guessed screen coordinates.
        fun pxX(x: Int) = (x.toLong() * displayWidth / 540L).toInt()
        fun pxY(y: Int) = (y.toLong() * displayHeight / 1170L).toInt()
        val target = LocalCheckTarget(LiveAction.CHECK, check.text,
            pxX(184), pxY(1108), pxX(352), pxY(1138))
        commandSeq++
        val ticket = LocalCheckTicket(commandSeq, epoch,
            ocr.ticket.sourcePackage, ocr.ticket.windowGeneration,
            cards.frameSequence, cards.hole.toList(), cards.community.toList(),
            target, LocalCheckSource.VISUAL_OCR_CALIBRATED, nowMs)
        pending = ticket
        confirmations = 2  // verified two DIFFERENT OCR JPEGs by independent gate
        reason = "CHECK по двум кадрам OCR + визуальная панель + карты"
        return ticket
    }

    /** Consume ONCE even if Android rejects the operation: never retry blind. */
    @Synchronized fun claim(ticket: LocalCheckTicket, nowMs: Long): Boolean {
        if (locked || awaitingDisappearance || ticket != pending ||
            ticket.issuedAtMs > nowMs || nowMs - ticket.issuedAtMs > 350L)
            return false
        pending = null
        lastCompletedStreetKey = listOf(ticket.packageName, ticket.captureEpoch.toString(),
            ticket.hole.joinToString(","), ticket.board.joinToString(",")).joinToString("|")
        awaitingDisappearance = true
        missingSamples = 0
        confirmations = 0
        count++
        reason = "CHECK отправлен в Android; результат игры ЕЩЁ НЕ подтверждён"
        return true
    }

    @Synchronized fun dispatchRejected() {
        stop("Android отклонил CHECK; требуется новый явный ARM, без повторов")
    }
}

/** Not an alternative path to paid BET/RAISE. */
object LocalPlayerCheckPilot {
    private val gate = LocalVisualCheckGate()
    fun start() = gate.arm()
    fun stop(reason: String = "STOP") = gate.stop(reason)
    fun status(): LocalCheckSnapshot = gate.status()
    fun isActive(): Boolean = gate.status().state != LocalCheckState.OFF
    fun observe(cards: StableCardReading?, native: NativeControlSnapshot?, pkg: String?,
                window: Long, epoch: Long, now: Long, width: Int, height: Int) =
        gate.observe(cards, native, pkg, window, epoch, now, width, height)
    fun offerVisualOcr(cards: StableCardReading?, ocr: AutomaticOcrEvidence?,
                       epoch: Long, now: Long, width: Int, height: Int) =
        gate.observeOcr(cards, ocr, epoch, now, width, height)
    fun claim(ticket: LocalCheckTicket, now: Long) = gate.claim(ticket, now)
    fun rejected() = gate.dispatchRejected()
}
