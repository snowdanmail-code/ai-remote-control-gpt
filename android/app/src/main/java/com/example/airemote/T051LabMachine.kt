package com.example.airemote

/**
 * T051 local action laboratory. The only permitted target is a button inside
 * our own non-exported test Activity; it never modifies the live game runtime.
 * All labels below are fixed diagnostic strings, never OCR or user content.
 */
enum class T051LabScenario { CHECK_OK, RAISE_REJECTED, MISSING_RECEIPT }
enum class T051LabPhase {
    IDLE, CONTRACT_REVIEW, BLOCKED, READY_TO_TAP,
    WAITING_ANDROID, WAITING_RECEIPT, CONFIRMED, FAILED, TIMED_OUT, STOPPED
}
data class T051LabReport(
    val phase: T051LabPhase, val token: Long,
    val scenario: T051LabScenario?,
    val events: List<String>,
    val reason: String
)

class T051LabMachine(private val timeoutMs: Long = 4_000L) {
    init { require(timeoutMs in 500L..60_000L) }
    private var sequence = 0L
    private var token = 0L
    private var phase = T051LabPhase.IDLE
    private var scenario: T051LabScenario? = null
    private var startedAtMs = 0L
    private var reason = "Лаборатория не запущена"
    private var androidCompleted = false
    private var uiReceipt = false
    private val events = ArrayDeque<String>()

    private fun log(message: String) {
        if (events.size >= 24) events.removeFirst()
        events.addLast(message)
    }

    @Synchronized fun start(newScenario: T051LabScenario, nowMs: Long): Long {
        token++
        phase = T051LabPhase.CONTRACT_REVIEW
        scenario = newScenario
        startedAtMs = nowMs
        androidCompleted = false
        uiReceipt = false
        events.clear()
        reason = "Проверяется JSON-решение на синтетической раздаче"
        log("01 · ТЕСТ: карты и ход заданы сценарием (НЕ данные 1win)")
        log("02 · Проверка формата и допустимости решения (без сети GPT)")
        return token
    }

    @Synchronized fun review(current: Long, accepted: Boolean, code: String): Boolean {
        if (current != token || phase != T051LabPhase.CONTRACT_REVIEW) return false
        if (!accepted) {
            phase = T051LabPhase.BLOCKED
            reason = "Решение отклонено локальным контрактом: ${safeCode(code)}"
            log("03 · БЛОКИРОВКА: ${safeCode(code)}. Касания нет")
        } else {
            phase = T051LabPhase.READY_TO_TAP
            reason = "Допустимый CHECK; ожидается жест только в тестовое окно"
            log("03 · Проверка CHECK пройдена. Это не решение облачного GPT")
        }
        return accepted
    }

    @Synchronized fun dispatched(current: Long): Boolean {
        if (current != token || phase != T051LabPhase.READY_TO_TAP) return false
        phase = T051LabPhase.WAITING_ANDROID
        reason = "Отправлено касание Android в тестовую кнопку"
        log("04 · Android: отправлена команда dispatchGesture в СВОЁ приложение")
        return true
    }

    @Synchronized fun gestureResult(current: Long, completed: Boolean) {
        if (current != token ||
            phase !in setOf(T051LabPhase.WAITING_ANDROID, T051LabPhase.WAITING_RECEIPT)) return
        if (!completed) {
            phase = T051LabPhase.FAILED
            reason = "Android отменил системный жест"
            log("05 · ОШИБКА: callback Android жест отменён")
            return
        }
        androidCompleted = true
        log("05 · Android подтвердил завершение системного жеста")
        reconcile()
    }

    @Synchronized fun appReceipt(current: Long, action: String): Boolean {
        if (current != token ||
            phase !in setOf(T051LabPhase.WAITING_ANDROID, T051LabPhase.WAITING_RECEIPT) ||
            action != "CHECK" || uiReceipt ||
            scenario == T051LabScenario.MISSING_RECEIPT) return false
        uiReceipt = true
        log("06 · Кнопка тестового приложения приняла CHECK")
        reconcile()
        return true
    }

    private fun reconcile() {
        if (androidCompleted && uiReceipt) {
            phase = T051LabPhase.CONFIRMED
            reason = "PASS: Android callback + подтверждение тестовой кнопки"
            log("07 · PASS: оба независимых этапа завершены")
        } else {
            phase = if (androidCompleted) T051LabPhase.WAITING_RECEIPT
                else T051LabPhase.WAITING_ANDROID
            reason = if (androidCompleted)
                "Жест Android завершён; нет подтверждения приложения"
            else "Кнопка зарегистрирована; ожидается ответ Android"
        }
    }

    @Synchronized fun reject(current: Long, reasonCode: String) {
        if (current != token ||
            phase !in setOf(T051LabPhase.READY_TO_TAP, T051LabPhase.WAITING_ANDROID))
            return
        phase = T051LabPhase.FAILED
        reason = "Касание не отправлено: ${safeCode(reasonCode)}"
        log("04 · ОШИБКА: ${safeCode(reasonCode)}")
    }

    @Synchronized fun tick(nowMs: Long) {
        if (phase in setOf(T051LabPhase.WAITING_ANDROID, T051LabPhase.WAITING_RECEIPT) &&
            nowMs >= startedAtMs && nowMs - startedAtMs > timeoutMs) {
            phase = T051LabPhase.TIMED_OUT
            reason = "Таймаут: нет двух подтверждений, повтор не выполняется"
            log("07 · TIMEOUT: действие осталось неподтверждённым")
        }
    }

    @Synchronized fun stop() {
        token++ // Cancel in-flight asynchronous callbacks.
        phase = T051LabPhase.STOPPED
        reason = "STOP: тест прекращён; повторов нет"
        log("STOP · все ожидающие результаты отозваны")
    }

    @Synchronized fun currentToken(): Long = token
    @Synchronized fun currentPhase(): T051LabPhase = phase
    @Synchronized fun currentScenario(): T051LabScenario? = scenario
    @Synchronized fun report(): T051LabReport =
        T051LabReport(phase, token, scenario, events.toList(), reason)

    private fun safeCode(raw: String): String =
        raw.takeIf { it in setOf(
            "NOT_LEGAL", "STALE_FRAME", "INVALID_SCHEMA",
            "AMOUNT_ACTION_MISMATCH", "NO_ACCESSIBILITY",
            "NOT_OWN_ACTIVITY", "STOP_REQUIRED", "CAPTURE_ACTIVE",
            "INVALID_TARGET", "DISPATCH_RETURNED_FALSE",
            "UNVERIFIED_STATE", "LAB_WINDOW_NOT_VISIBLE"
        ) } ?: "REJECTED"
}
