package com.example.airemote
import java.util.ArrayDeque

/** A strictly allow-listed, image-free audit trail. NEVER put OCR text, usernames, tokens,
 * complete package names, exception messages, card images or hand/table identifiers here.
 * All timestamps are monotonic elapsed milliseconds within a single Android boot.
 */
enum class IncidentSeverity { INFO, WARNING, ERROR, CRITICAL }
enum class IncidentCode {
    SESSION_ARMED, OPERATOR_STOP, CAPTURE_STARTED, CAPTURE_STOPPED,
    CAPTURE_FRAME_ERROR, CAPTURE_PERMISSION_ERROR, FOREGROUND_CHANGED,
    OBSERVATION_UNVERIFIED, OCR_ENGINE_ERROR, OCR_CONFLICT, OCR_STALE,
    DECISION_MISSING, DECISION_REJECTED, DECISION_EXPIRED,
    COMMAND_REQUESTED, COMMAND_DISPATCHED, GESTURE_COMPLETED_OS,
    COMMAND_REJECTED, COMMAND_CONFIRMED,
    COMMAND_UNCERTAIN, CONTEXT_CHANGED, SAFETY_BLOCKED,
    OPPONENT_OBSERVATION_REJECTED, OPPONENT_CONTEXT_INVALIDATED, OPPONENT_HAND_STORAGE_FAILED
}

/** Controlled schema with no free-form user or model text. */
data class IncidentEvent(
    val atElapsedMs: Long,
    val severity: IncidentSeverity,
    val code: IncidentCode,
    val captureEpoch: Long? = null,
    val frameSequence: Long? = null,
    val latencyMs: Long? = null,
    val action: String? = null,
    val stage: String? = null,
    val potChips: Long? = null,
    val callChips: Long? = null,
    val timerRemainingMs: Long? = null,
    val legalActions: Set<String> = emptySet()
)

object IncidentFormat {
    private val allowedActions = setOf("FOLD", "CHECK", "CALL", "BET", "RAISE", "CONFIRM")
    private val allowedStages = setOf("PREFLOP", "FLOP", "TURN", "RIVER", "SHOWDOWN", "UNKNOWN")
    fun encode(event: IncidentEvent, sequence: Long, runId: String = ""): String {
        val action = event.action?.uppercase()?.takeIf { it in allowedActions }
        val stage = event.stage?.uppercase()?.takeIf { it in allowedStages }
        val legal = event.legalActions.mapNotNull { it.uppercase().takeIf { v -> v in allowedActions } }
            .distinct().sorted()
        fun number(key: String, value: Long?): String =
            if (value == null || value < 0L) "" else ",\"$key\":$value"
        return buildString {
            append("{\"sequence\":").append(sequence)
            append(",\"elapsed_ms\":").append(event.atElapsedMs.coerceAtLeast(0L))
            append(",\"severity\":\"").append(event.severity.name).append('"')
            append(",\"code\":\"").append(event.code.name).append('"')
            val safeRun = runId.take(40).filter { it.isLetterOrDigit() || it == '-' }
            if (safeRun.isNotBlank()) append(",\"run_id\":\"").append(safeRun).append('"')
            append(number("capture_epoch", event.captureEpoch))
            append(number("frame_sequence", event.frameSequence))
            append(number("latency_ms", event.latencyMs))
            if (action != null) append(",\"action\":\"").append(action).append('"')
            if (stage != null) append(",\"stage\":\"").append(stage).append('"')
            append(number("pot_chips", event.potChips))
            append(number("call_chips", event.callChips))
            append(number("timer_remaining_ms", event.timerRemainingMs))
            if (legal.isNotEmpty()) {
                append(",\"legal_actions\":[")
                append(legal.joinToString(",") { "\"$it\"" })
                append(']')
            }
            append('}')
        }
    }

    /** Only app-generated JSONL goes here; bounded counts avoid huge exports. */
    fun reportJson(lines: List<String>, version: String, sessionToken: String): String {
        val safeLines = lines.takeLast(400).filter {
            it.length <= 512 && it.startsWith("{\"sequence\":") && it.endsWith("}") &&
                !it.contains('\n') && !it.contains('\r')
        }
        // Version and random session token have strict syntactic whitelists.
        val safeVersion = version.take(40).filter { it.isLetterOrDigit() || it in "._-" }
        val safeToken = sessionToken.take(60).filter { it.isLetterOrDigit() || it == '-' }
        return "{\"schema\":1,\"version\":\"$safeVersion\"," +
            "\"session\":\"$safeToken\",\"events\":[" + safeLines.joinToString(",") + "]}"
    }
}

/** Bounded in-memory copy; Android persistence is maintained by OnDeviceIncidentRecorder. */
class IncidentJournal(private val capacity: Int = 400, private val runId: String = "") {
    init { require(capacity in 1..5000) }
    private val events = ArrayDeque<String>()
    private var nextSequence = 1L

    @Synchronized fun append(event: IncidentEvent): String {
        val line = IncidentFormat.encode(event, nextSequence++, runId)
        if (events.size >= capacity) events.removeFirst()
        events.addLast(line)
        return line
    }

    @Synchronized fun snapshot(): List<String> = events.toList()
    @Synchronized fun size(): Int = events.size
}
