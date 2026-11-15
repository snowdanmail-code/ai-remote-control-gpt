package com.example.airemote

/**
 * Minimum buy-in only. No auto-rebuy/auto-top-up. The 1.60 amount in the
 * example is NOT hard-coded: it comes from the current table's Min OCR.
 * Only an operator-armed seating session may use this read-only decision.
 */
enum class OneWinMinBuyInStep { RECHECK_OCR, PRESS_MIN, PRESS_OK }

data class OneWinMinBuyInDecision(
    val step: OneWinMinBuyInStep,
    val target: NormalizedTextBox? = null,
    val amountCents: Long? = null,
    val explanation: String = ""
)

object OneWinMinimumBuyInPolicy {
    fun evaluate(reading: OneWinBuyInDialogReading?,
                 limits: OneWinCashTableLimits?,
                 minimumAlreadyTapped: Boolean,
                 selectedTable: OneWinCashLobbyTable? = null): OneWinMinBuyInDecision {
        fun unknown(reason: String) = OneWinMinBuyInDecision(
            OneWinMinBuyInStep.RECHECK_OCR, explanation = reason)
        if (reading?.status != OneWinBuyInReadingStatus.VERIFIED ||
            limits?.valid() != true) return unknown("Окно или лимиты не подтверждены")
        val min = reading.minimumCents ?: return unknown("Минимальная сумма не прочитана")
        val max = reading.maximumCents ?: return unknown("Максимальная сумма не прочитана")
        val big = reading.bigBlindCents ?: return unknown("Блайнды не прочитаны")
        val small = reading.smallBlindCents ?: return unknown("Блайнды не прочитаны")
        if (selectedTable != null && (small != selectedTable.smallBlindCents ||
                big != selectedTable.bigBlindCents || min != selectedTable.minBuyInCents))
            return unknown("Блайнды или минимум отличаются от выбранного стола")
        val balance = reading.availableCents ?: return unknown("Баланс не прочитан")
        val actual = reading.inputCents ?: return unknown("Поле бай-ина не прочитано")
        val debit = reading.debitCents ?: return unknown("Списание не прочитано")
        if (min <= 0 || min > max || min > limits.maxBuyInCents ||
            balance < min || small <= 0 || big <= small ||
            big > limits.maxBigBlindCents || debit != actual ||
            actual !in min..max)
            return unknown("Минимум или суммы не согласованы с лимитами")
        if (actual != min) {
            if (minimumAlreadyTapped) return unknown(
                "Мин уже нажимали, но новое значение не подтвердилось")
            val button = reading.minButtonBox
            if (button?.valid() != true || button.centerX() !in 30..340 ||
                button.centerY() !in 277..389)
                return unknown("Кнопка «Мин» не распознана")
            return OneWinMinBuyInDecision(OneWinMinBuyInStep.PRESS_MIN,
                button, min, "Выставить минимальный бай-ин; затем заново прочитать окно")
        }
        if (!reading.withinLimits(limits))
            return unknown("Вход не прошёл полную проверку лимитов")
        val ok = reading.okBox
        if (ok?.valid() != true || ok.centerX() !in 350..680 ||
            ok.centerY() !in 916..991)
            return unknown("Кнопка «ОК» не подтверждена")
        return OneWinMinBuyInDecision(OneWinMinBuyInStep.PRESS_OK, ok, min,
            "Минимум, сумма списания и поле ввода совпадают; разрешено одно подтверждение")
    }
}

/** One-shot, short-lived gesture proof from the same OCR-window/session. */
enum class OneWinBuyInTapPurpose { CHOOSE_MIN, CONFIRM_OK }
data class OneWinBuyInTapTicket(
    val requestId: Long,
    val purpose: OneWinBuyInTapPurpose,
    val sourcePackage: String,
    val captureEpoch: Long,
    val windowGeneration: Long,
    val frameSequence: Long,
    val observedAtMs: Long,
    val capturedAtMs: Long,
    val frameWidth: Int,
    val frameHeight: Int,
    val minimumCents: Long,
    val target: NormalizedTextBox,
    val expected: OneWinBuyInDialogReading
)
