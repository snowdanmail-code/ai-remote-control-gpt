package com.example.airemote

import org.json.JSONObject

/**
 * A one-time delivery receipt for the OAuth ChatGPT-plan transport.
 *
 * IMPORTANT: with store=false, each Responses request is stateless. An ACK
 * only proves that one request was received, NOT that any ChatGPT conversation
 * will remember the opponent statistics. Verified-turn prompts must include
 * the relevant session context again on EVERY request.
 */
data class ChatGptPlanHandoff(
    val nonce: String,
    val seatCount: Int,
    val prompt: String
)

object ChatGptPlanHandoffProtocol {
    private const val SCHEMA = "t045.session-context.ack.v1"
    private val safe = Regex("""[A-Za-z0-9_:=.,;]+""")
    private val noncePattern = Regex("""[A-Za-z0-9_-]{18,90}""")
    private val seatHead = Regex("""seat_([0-9]):([A-Za-z0-9_=.,]+)""")
    private val decimal = Regex("""[0-9]{1,6}(?:\.[0-9])?""")

    fun prepare(stats: String, nonce: String): ChatGptPlanHandoff? {
        if (stats.isBlank() || stats.length > 900 ||
            !safe.matches(stats) || !noncePattern.matches(nonce)) return null
        val entries = stats.split(';')
        if (entries.size !in 1..5 || entries.any { it.isBlank() }) return null
        val seats = entries.map { line ->
            val match = seatHead.matchEntire(line) ?: return null
            val seat = match.groupValues[1].toInt()
            val values = match.groupValues[2].split(',')
            if (values.size !in 1..11) return null
            val names = mutableSetOf<String>()
            for (metric in values) {
                val key = metric.substringBefore('=', "")
                val value = metric.substringAfter('=', "")
                if (key !in OpponentPopupMetric.entries.map { it.name } ||
                    !names.add(key) || !decimal.matches(value)) return null
                val number = value.toDoubleOrNull() ?: return null
                val definition = OpponentPopupMetric.entries.first { it.name == key }
                if (!number.isFinite() || number < 0.0 ||
                    number > (if (definition.percent) 100.0 else 100_000.0) ||
                    (definition == OpponentPopupMetric.HANDS && number % 1.0 != 0.0))
                    return null
            }
            seat
        }
        if (seats.toSet().size != seats.size) return null
        val prompt = "This is a read-only delivery check for virtual-chip poker table " +
            "observations. Values are unverified OCR and contain no player identity. " +
            "Do not make a poker move. Reply ONLY a JSON object with exactly " +
            "schema, nonce, seats; schema=$SCHEMA, nonce=$nonce, " +
            "seats=${seats.size}. No markdown. Context: $stats"
        return prompt.takeIf { it.length <= 1600 }?.let {
            ChatGptPlanHandoff(nonce, seats.size, it)
        }
    }

    fun verifyAck(answer: String, ticket: ChatGptPlanHandoff): Boolean {
        if (answer.length !in 2..512 || !answer.startsWith('{') ||
            !answer.endsWith('}')) return false
        val obj = try { JSONObject(answer) } catch (_: Exception) { return false }
        if (obj.keys().asSequence().toSet() != setOf("schema", "nonce", "seats"))
            return false
        return obj.optString("schema") == SCHEMA &&
            obj.optString("nonce") == ticket.nonce &&
            obj.opt("seats") is Number &&
            (obj.opt("seats") as Number).toInt() == ticket.seatCount &&
            (obj.opt("seats") as Number).toDouble() == ticket.seatCount.toDouble()
    }
}
