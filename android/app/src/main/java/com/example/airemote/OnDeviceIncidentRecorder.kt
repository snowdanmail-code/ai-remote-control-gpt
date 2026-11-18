package com.example.airemote

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.util.UUID
import java.util.ArrayDeque

/** Private, bounded diagnostic log; no network permissions and no screenshots.
 * Logs are only exported by explicit user action through Android's document picker.
 */
object OnDeviceIncidentRecorder {
    private const val MAX_FILE_BYTES = 160_000L
    private const val MAX_EXPORT_EVENTS = 400
    private const val LOG_NAME = "incident_events.jsonl"
    private const val OLD_LOG_NAME = "incident_events.previous.jsonl"
    private val session = UUID.randomUUID().toString()
    private val journal = IncidentJournal(MAX_EXPORT_EVENTS, session)
    @Volatile private var errors = 0
    @Volatile private var loggingFailed = false

    @Synchronized fun record(context: Context, code: IncidentCode,
        severity: IncidentSeverity = IncidentSeverity.INFO,
        captureEpoch: Long? = null, frameSequence: Long? = null,
        latencyMs: Long? = null, action: String? = null,
        stage: String? = null, potChips: Long? = null, callChips: Long? = null,
        timerRemainingMs: Long? = null, legalActions: Set<String> = emptySet()) {
        try {
            val app = context.applicationContext
            val event = IncidentEvent(SystemClock.elapsedRealtime(), severity, code,
                captureEpoch, frameSequence, latencyMs, action,
                stage, potChips, callChips, timerRemainingMs, legalActions)
            val line = journal.append(event)
            val current = File(app.filesDir, LOG_NAME)
            if (current.length() + line.length + 1 > MAX_FILE_BYTES) {
                val old = File(app.filesDir, OLD_LOG_NAME)
                if (old.exists()) old.delete()
                if (!current.renameTo(old)) current.delete()
            }
            current.appendText(line + "\n", Charsets.UTF_8)
            if (severity == IncidentSeverity.ERROR || severity == IncidentSeverity.CRITICAL) errors++
        } catch (_: Exception) {
            // Diagnostics must never prevent STOP or break capture/service execution.
            loggingFailed = true
        }
    }

    @Synchronized fun exportReport(context: Context): String {
        val base = context.applicationContext.filesDir
        val lines = sequenceOf(OLD_LOG_NAME, LOG_NAME).flatMap { name ->
            val file = File(base, name)
            if (!file.exists()) emptySequence() else file.useLines { it.toList().asSequence() }.toList().asSequence()
        }.takeLastLimited(MAX_EXPORT_EVENTS)
        @Suppress("DEPRECATION")
        val version = appVersion(context)
        return IncidentFormat.reportJson(lines, version, session)
    }

    @Suppress("DEPRECATION")
    private fun appVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    } catch (_: Exception) { "unknown" }

    private fun <T> Sequence<T>.takeLastLimited(size: Int): List<T> {
        val buffer = ArrayDeque<T>()
        for (item in this) {
            if (buffer.size >= size) buffer.removeFirst()
            buffer.addLast(item)
        }
        return buffer.toList()
    }

    fun shortStatus(): String = "Событий в памяти: ${journal.size()}, ошибок в этом запуске: $errors" +
        if (loggingFailed) "; запись на диск потребовала проверки" else "; локальный журнал включён"
}
