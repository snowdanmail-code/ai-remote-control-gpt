package com.example.airemote

import android.content.Context

/**
 * T052: explicit read-only OCR probe for the cash lobby.
 *
 * Unlike the automatic selection session this toggle never invokes a gesture,
 * requests a seat, sets limits, or authorizes a buy-in. The capture service
 * only routes images to the existing two-frame OCR consensus while enabled.
 */
object OnDeviceCashLobbyReadOnlyProbe {
    @Volatile private var enabled = false
    @Volatile private var lastStatus = "Только OCR: выключен"

    @Synchronized fun enabled(): Boolean = enabled

    @Synchronized fun start(context: Context): Boolean {
        if (enabled || !FrameStore.isCapturing ||
            OperatorStopStore.isStopped(context) ||
            CaptureObservationMode.isSpectator() ||
            OnDeviceCashLobbySelection.scanning()) {
            lastStatus = "Только OCR: нужен активный захват в режиме игрока, " +
                "STOP выключен, автоматический выбор стола не запущен"
            return false
        }
        OnDeviceOneWinCashLobby.reset()
        OnDeviceCashLobbyTextRecognition.lastStatus = "OCR: ожидание внешнего экрана 1win"
        enabled = true
        lastStatus = "Только OCR: включен; откройте экран «Кэш-Игры» в 1win"
        return true
    }

    @Synchronized fun stop() {
        enabled = false
        lastStatus = "Только OCR: остановлен"
    }

    fun summary(): String {
        val observed = OnDeviceOneWinCashLobby.latest()
        val count = observed?.tables?.size ?: 0
        val names = observed?.tables?.take(6)?.joinToString("; ") {
            "${it.label} ${it.occupiedSeats}/${it.totalSeats} " +
                "BB=${it.bigBlindCents}¢"
        } ?: "нет двух согласованных кадров списка"
        return "${if (enabled) "READ_ONLY_RUNNING" else "READ_ONLY_OFF"}; " +
            "$lastStatus; ${OnDeviceCashLobbyTextRecognition.lastStatus}; " +
            "подтверждено столов: $count; $names"
    }
}
