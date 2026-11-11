package com.example.airemote

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * T043 json-semantic.v1: strict UTF-8, lossless decimals, duplicate-key rejection,
 * Unicode scalar values, UTF-8 byte-order keys and recursive deterministic JSON.
 * It is intentionally NOT org.json (which can round large integers).
 */
object T043MessageFingerprint {
    const val JSON_CANONICALIZATION = "json-semantic.v1"
    const val RAW_CANONICALIZATION = "raw-bytes.v1"
    const val MAX_JSON_BYTES = 1024 * 1024

    data class Digest(
        val rawSha256: String,
        val semanticSha256: String?,
        val comparisonMode: String,
        val comparisonDigest: String,
        val quarantine: Boolean
    )

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    fun digest(bytes: ByteArray, encoding: String, canonicalization: String): Digest {
        val snapshot = bytes.copyOf()
        val raw = sha256(snapshot)
        if (encoding == "OPAQUE_BYTES" && canonicalization == RAW_CANONICALIZATION)
            return Digest(raw, null, "RAW_BYTES", raw, false)
        if (encoding == "JSON_UTF8" && canonicalization == JSON_CANONICALIZATION) {
            return try {
                val canonical = canonicalJson(snapshot)
                val semantic = sha256(canonical)
                Digest(raw, semantic, "SEMANTIC_JSON", semantic, false)
            } catch (_: IllegalArgumentException) {
                Digest(raw, null, "RAW_BYTES", raw, true)
            }
        }
        return Digest(raw, null, "RAW_BYTES", raw, true)
    }

    fun canonicalJson(bytes: ByteArray): ByteArray {
        require(bytes.size <= MAX_JSON_BYTES) { "JSON size limit" }
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val input = try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: Exception) {
            throw IllegalArgumentException("Malformed UTF-8", e)
        }
        require(!input.startsWith("\uFEFF")) { "BOM not allowed" }
        return JsonReader(input).parse().toByteArray(StandardCharsets.UTF_8)
    }

    /** Stable immutable envelope fields; delivery status and local clock are excluded. */
    data class Envelope(
        val senderId: String,
        val messageId: String,
        val recipientId: String,
        val streamId: String,
        val sequenceNo: Long,
        val contentType: String,
        val sourceNamespace: String? = null,
        val relatedRecordType: String? = null,
        val relatedRecordKey: String? = null,
        val rulesetId: String? = null,
        val payloadEncoding: String = "JSON_UTF8",
        val schemaMajor: Int? = null,
        val schemaMinor: Int? = null,
        val canonicalizationId: String = JSON_CANONICALIZATION
    ) {
        init {
            require(listOf(senderId,messageId,recipientId,streamId,contentType).all {
                it.isNotBlank() && it.length <= 128
            })
            require(sequenceNo > 0)
            require((relatedRecordType == null) == (relatedRecordKey == null))
            require((schemaMajor == null) == (schemaMinor == null))
            require(schemaMajor == null || schemaMajor > 0)
            require(schemaMinor == null || schemaMinor >= 0)
        }
    }

    fun envelopeSha256(header: Envelope, digest: Digest): String {
        val output = ByteArrayOutputStream()
        output.write("T043-ENVELOPE-v1".toByteArray(StandardCharsets.US_ASCII))
        output.write(0)
        val components = listOf(
            header.senderId,header.messageId,header.recipientId,header.streamId,
            header.sequenceNo.toString(),header.contentType,header.sourceNamespace,
            header.relatedRecordType,header.relatedRecordKey,header.rulesetId,
            header.payloadEncoding,header.schemaMajor?.toString(),header.schemaMinor?.toString(),
            header.canonicalizationId,digest.comparisonMode,digest.comparisonDigest
        )
        components.forEach { part ->
            if (part == null) output.write(0)
            else {
                val utf8 = part.toByteArray(StandardCharsets.UTF_8)
                require(utf8.size.toLong() <= 0xFFFFFFFFL)
                output.write(1)
                val size = utf8.size
                output.write((size ushr 24) and 255)
                output.write((size ushr 16) and 255)
                output.write((size ushr 8) and 255)
                output.write(size and 255)
                output.write(utf8)
            }
        }
        return sha256(output.toByteArray())
    }

    private class JsonReader(private val text: String) {
        private var pos = 0
        private var nodes = 0
        fun parse(): String {
            val result = value(0)
            ws()
            require(pos == text.length) { "Trailing JSON input" }
            return result
        }
        private fun ws() {
            while (pos < text.length && text[pos] in " \n\r\t") pos++
        }
        private fun peek(): Char? = text.getOrNull(pos)
        private fun take(expected: Char) {
            require(peek() == expected) { "Expected $expected at $pos" }
            pos++
        }
        private fun value(depth: Int): String {
            require(depth <= 64 && ++nodes <= 100000) { "JSON depth/nodes limit" }
            ws()
            return when (peek()) {
                '{' -> obj(depth + 1)
                '[' -> array(depth + 1)
                '"' -> quote(string())
                't' -> literal("true")
                'f' -> literal("false")
                'n' -> literal("null")
                '-', in '0'..'9' -> number()
                else -> throw IllegalArgumentException("Unexpected token at $pos")
            }
        }
        private fun literal(expected: String): String {
            require(text.startsWith(expected, pos)) { "Invalid literal" }
            pos += expected.length
            return expected
        }
        private fun obj(depth: Int): String {
            take('{'); ws()
            val map = HashMap<String, String>()
            if (peek() == '}') { pos++; return "{}" }
            while (true) {
                ws(); require(peek() == '"') { "JSON object key expected" }
                val key = string()
                require(!map.containsKey(key)) { "Duplicate JSON key" }
                ws(); take(':')
                map[key] = value(depth)
                ws()
                when (peek()) {
                    '}' -> { pos++; break }
                    ',' -> { pos++; ws(); require(peek() != '}') { "Trailing comma" } }
                    else -> throw IllegalArgumentException("Invalid object separator")
                }
            }
            val keys = map.keys.sortedWith(Comparator { a, b ->
                val x = a.toByteArray(StandardCharsets.UTF_8)
                val y = b.toByteArray(StandardCharsets.UTF_8)
                for (i in 0 until minOf(x.size, y.size)) {
                    val diff = (x[i].toInt() and 255) - (y[i].toInt() and 255)
                    if (diff != 0) return@Comparator diff
                }
                x.size - y.size
            })
            return keys.joinToString(",", "{", "}") { quote(it) + ":" + map.getValue(it) }
        }
        private fun array(depth: Int): String {
            take('['); ws()
            if (peek() == ']') { pos++; return "[]" }
            val children = ArrayList<String>()
            while (true) {
                children.add(value(depth))
                ws()
                when (peek()) {
                    ']' -> { pos++; break }
                    ',' -> { pos++; ws(); require(peek() != ']') { "Trailing comma" } }
                    else -> throw IllegalArgumentException("Invalid array separator")
                }
            }
            return children.joinToString(",", "[", "]")
        }
        private fun string(): String {
            take('"')
            val result = StringBuilder()
            while (true) {
                val c = peek() ?: throw IllegalArgumentException("Unclosed string")
                pos++
                if (c == '"') break
                if (c == '\\') {
                    val escaped = peek() ?: throw IllegalArgumentException("Bad JSON escape")
                    pos++
                    when (escaped) {
                        '"', '\\', '/' -> result.append(escaped)
                        'b' -> result.append('\b')
                        'f' -> result.append('\u000c')
                        'n' -> result.append('\n')
                        'r' -> result.append('\r')
                        't' -> result.append('\t')
                        'u' -> {
                            val code = unicodeHex()
                            if (code in 0xD800..0xDBFF) {
                                require(pos + 2 <= text.length && text.substring(pos,pos+2) == "\\u") {
                                    "High surrogate without partner"
                                }
                                pos += 2
                                val low = unicodeHex()
                                require(low in 0xDC00..0xDFFF) { "Invalid surrogate partner" }
                                result.append(code.toChar()).append(low.toChar())
                            } else {
                                require(code !in 0xDC00..0xDFFF) { "Lone low surrogate" }
                                result.append(code.toChar())
                            }
                        }
                        else -> throw IllegalArgumentException("Bad JSON escape")
                    }
                } else if (c == '\u0000' || c.code < 32) {
                    throw IllegalArgumentException("Unescaped control")
                } else if (c.isHighSurrogate()) {
                    val partner = peek()
                    require(partner != null && partner.isLowSurrogate()) { "Invalid UTF-16 pair" }
                    result.append(c).append(partner)
                    pos++
                } else {
                    require(!c.isLowSurrogate()) { "Lone low surrogate" }
                    result.append(c)
                }
            }
            return result.toString()
        }
        private fun unicodeHex(): Int {
            require(pos + 4 <= text.length) { "Short Unicode escape" }
            val digits = text.substring(pos,pos+4)
            require(digits.all { it in "0123456789abcdefABCDEF" })
            pos += 4
            return digits.toInt(16)
        }
        private fun quote(value: String): String {
            val sb = StringBuilder("\"")
            for (char in value) {
                when (char) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    else -> if (char.code < 32) {
                        sb.append("\\u").append(char.code.toString(16).padStart(4,'0'))
                    } else sb.append(char)
                }
            }
            return sb.append('"').toString()
        }
        private fun number(): String {
            val neg = if (peek() == '-') { pos++; true } else false
            val integerStart = pos
            when (peek()) {
                '0' -> { pos++; require(peek() !in '0'..'9') { "Leading zero" } }
                in '1'..'9' -> while (peek() in '0'..'9') pos++
                else -> throw IllegalArgumentException("Number missing integer")
            }
            val integer = text.substring(integerStart,pos)
            var fraction = ""
            if (peek() == '.') {
                pos++
                val start = pos
                while (peek() in '0'..'9') pos++
                require(pos > start) { "Empty decimal fraction" }
                fraction = text.substring(start,pos)
            }
            var exponent = BigInteger.ZERO
            if (peek() == 'e' || peek() == 'E') {
                pos++
                val sign = if (peek() == '-' || peek() == '+') text[pos++].toString() else ""
                val start = pos
                while (peek() in '0'..'9') pos++
                require(pos > start && pos-start <= 2048) { "Bad exponent" }
                exponent = BigInteger(sign + text.substring(start,pos))
            }
            var coefficient = (integer + fraction).trimStart('0')
            if (coefficient.isEmpty()) return "0"
            exponent = exponent.subtract(BigInteger.valueOf(fraction.length.toLong()))
            val trimmed = coefficient.trimEnd('0')
            exponent = exponent.add(BigInteger.valueOf((coefficient.length-trimmed.length).toLong()))
            coefficient = trimmed
            return (if (neg) "-" else "") + coefficient +
                (if (exponent == BigInteger.ZERO) "" else "e$exponent")
        }
    }
}
