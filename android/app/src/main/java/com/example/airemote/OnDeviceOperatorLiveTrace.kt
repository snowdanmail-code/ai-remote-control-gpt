package com.example.airemote

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject
import java.util.ArrayDeque

/**
 * Read-only Samsung test console. Sources are allow-listed internal states and
 * the existing redacted IncidentJournal: never OCR text, nicknames, images,
 * cloud responses, access tokens or raw exception messages.
 * Nothing here can dispatch gestures or authorize gameplay.
 */
data class OperatorLiveSnapshot(
    val overview: String,
    val recent: List<String>,
    val compact: String
)

object OperatorEventText {
    fun russian(code: IncidentCode): String = when (code) {
        IncidentCode.SESSION_ARMED -> "Разрешена новая сессия"
        IncidentCode.OPERATOR_STOP -> "STOP: всё остановлено"
        IncidentCode.CAPTURE_STARTED -> "Захват экрана запущен"
        IncidentCode.CAPTURE_STOPPED -> "Захват экрана остановлен"
        IncidentCode.CAPTURE_FRAME_ERROR -> "Ошибка получения кадра"
        IncidentCode.CAPTURE_PERMISSION_ERROR -> "Нет разрешения на захват"
        IncidentCode.FOREGROUND_CHANGED -> "Изменилось активное окно"
        IncidentCode.OBSERVATION_UNVERIFIED -> "Состояние раздачи не подтверждено"
        IncidentCode.OCR_ENGINE_ERROR -> "Ошибка распознавания OCR"
        IncidentCode.OCR_CONFLICT -> "Распознавание кадров противоречиво"
        IncidentCode.OCR_STALE -> "Кадр OCR устарел"
        IncidentCode.DECISION_MISSING -> "Нет допустимого решения ИИ"
        IncidentCode.DECISION_REJECTED -> "Решение ИИ отклонено"
        IncidentCode.DECISION_EXPIRED -> "Решение ИИ просрочено"
        IncidentCode.COMMAND_REQUESTED -> "Получен запрос на действие"
        IncidentCode.COMMAND_DISPATCHED -> "Команда передана Android"
        IncidentCode.GESTURE_COMPLETED_OS -> "Android завершил жест (не доказательство хода)"
        IncidentCode.COMMAND_REJECTED -> "Команда заблокирована"
        IncidentCode.COMMAND_CONFIRMED -> "Результат действия подтверждён"
        IncidentCode.COMMAND_UNCERTAIN -> "Результат действия неизвестен; повтор запрещён"
        IncidentCode.CONTEXT_CHANGED -> "Контекст/раздача изменились"
        IncidentCode.SAFETY_BLOCKED -> "Сработала защита"
        IncidentCode.OPPONENT_OBSERVATION_REJECTED -> "Наблюдение соперника отклонено"
        IncidentCode.OPPONENT_CONTEXT_INVALIDATED -> "Память мест соперников сброшена"
        IncidentCode.OPPONENT_HAND_STORAGE_FAILED -> "Не удалось сохранить историю раздачи"
    }

    fun line(sequence: Long, code: IncidentCode, action: String? = null): String {
        // Action is already allow-listed by IncidentFormat; never print other text.
        val safeAction = action?.takeIf { it in
            setOf("FOLD", "CHECK", "CALL", "BET", "RAISE", "CONFIRM") }
        return "#$sequence " + russian(code) +
            (if (safeAction == null) "" else " · $safeAction")
    }
}

object OnDeviceOperatorLiveTrace {
    private const val MAX_ENTRIES = 48
    private val timeline = ArrayDeque<String>()
    private val lastState = mutableMapOf<String, String>()
    private var lastJournalSeq = 0L

    /** The overlay is opt-in; diagnostic data stays within the app process. */
    fun overlayEnabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences("operator_live", Context.MODE_PRIVATE)
            .getBoolean("show_overlay", false)

    fun setOverlayEnabled(context: Context, value: Boolean) {
        context.applicationContext.getSharedPreferences("operator_live", Context.MODE_PRIVATE)
            .edit().putBoolean("show_overlay", value).apply()
    }

    private fun append(entry: String) {
        if (timeline.size >= MAX_ENTRIES) timeline.removeFirst()
        timeline.addLast(entry)
    }

    private fun short(value: String, max: Int = 105): String =
        value.replace('\n', ' ').replace('\r', ' ').trim().take(max)

    private fun changed(section: String, value: String, now: Long) {
        val safe = short(value)
        if (lastState.put(section, safe) != safe)
            append("${now / 1000L}s · $section: $safe")
    }

    /** Idempotent, bounded read; safe to call from Activity and accessibility UI. */
    @Synchronized fun snapshot(context: Context, limit: Int = 10): OperatorLiveSnapshot {
        val now = SystemClock.elapsedRealtime()
        // All incident strings were produced by IncidentFormat, never by OCR.
        for (line in OnDeviceIncidentRecorder.liveLines(30)) {
            try {
                val json = JSONObject(line)
                val sequence = json.optLong("sequence", -1)
                if (sequence <= lastJournalSeq) continue
                val code = IncidentCode.valueOf(json.getString("code"))
                append("${json.optLong("elapsed_ms", now) / 1000L}s · " +
                    OperatorEventText.line(sequence, code, json.optString("action")))
                lastJournalSeq = sequence
            } catch (_: Exception) { /* malformed diagnostic record is ignored */ }
        }
        val stop = OperatorStopStore.isStopped(context)
        val capture = FrameStore.isCapturing
        val auto = OnDeviceAutonomousRuntime.status()
        val cycle = OnDeviceAuthorizedPlayerCycle.status()
        val gpt = OnDeviceVerifiedGptTurnRequests.status()
        // Only enum phases are read from screen navigation / profile workflows.
        // Table titles, popup text and player nicknames never enter the timeline.
        val lobbyPhase = OnDeviceCashLobbySelection.status().phase.name
        val seatingPhase = OnDeviceOneWinPreSeatScanner.current().phase.name
        val profilePhase = OnDeviceOpponentProfileSweep.status().phase.name
        val studyPhase = OnDeviceOneWinPostSeatStudy.status().phase.name
        val autoLine = "${auto.state}: ${auto.explanation}"
        changed("STOP", if (stop) "Активен" else "Выключен", now)
        changed("Захват", if (capture) "Активен" else "Неактивен", now)
        changed("AUTO", autoLine, now)
        changed("GPT", gpt, now)
        changed("Проверка хода", "${cycle.stage}: ${cycle.description}", now)
        changed("Поиск стола", lobbyPhase, now)
        changed("Посадка", seatingPhase, now)
        changed("Профили соперников", profilePhase, now)
        changed("Изучение соперников", studyPhase, now)
        val overview = "STOP: ${if (stop) "ДА" else "нет"} · " +
            "Кадры: ${FrameStore.receivedImages} · " +
            "Захват: ${if (capture) "да" else "нет"}\n" +
            "ИИ: ${short(autoLine, 135)}\n" +
            "${short(gpt, 135)}\n" +
            "Ход: ${short(cycle.stage.name + ": " + cycle.description, 135)}\n" +
            "Стол: ${lobbyPhase} · Место: ${seatingPhase} · " +
            "Профили: ${profilePhase} · Изучение: ${studyPhase}"
        val recent = timeline.toList().takeLast(limit.coerceIn(1, MAX_ENTRIES)).asReversed()
        val compact = "Стол: ${short(lobbyPhase, 18)} · Место: ${short(seatingPhase, 22)}\n" +
            "ИИ: ${short(autoLine, 65)}\n" +
            "${short(gpt, 78)}\n" +
            (recent.firstOrNull() ?: "Нет событий")
        return OperatorLiveSnapshot(overview, recent, compact)
    }
}
