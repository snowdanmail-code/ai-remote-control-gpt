package com.example.airemote

/** 1win displays FREE virtual chips with a '$' glyph. This is not money
 * detection, a deposit permission, or a wager authorization.
 *
 * We store all quantities as integer hundredths of a displayed chip unit.
 * No floating-point rounding and no silently treating unknown as zero.
 */
data class VirtualChipLabel(val kind: NumericCandidateKind,
                            val hundredths: Long, val original: String)

object VirtualChipDisplayParser {
    private val pot = Regex("^(?:pot|банк)\\s*[:=]?\\s*(.+)$", RegexOption.IGNORE_CASE)
    private val call = Regex("^(?:call|колл|уравнять|ответить)\\s*[:=]?\\s*(.+)$",
        RegexOption.IGNORE_CASE)
    private val amount = Regex("^\\s*(\\d{1,9})(?:[.,](\\d{1,2}))?\\s*[$]\\s*$")
    private val normalizedDecimal = Regex("^\\s*(\\d{1,9})(?:[.,](\\d{1,2}))?\\s*$")

    fun parseHundredths(raw: String): Long? {
        val match = amount.matchEntire(raw) ?: normalizedDecimal.matchEntire(raw) ?: return null
        val whole = match.groupValues[1].toLongOrNull() ?: return null
        val fraction = match.groupValues[2]
        val part = when(fraction.length) {
            0 -> 0L
            1 -> fraction.toLongOrNull()?.times(10L) ?: return null
            2 -> fraction.toLongOrNull() ?: return null
            else -> return null
        }
        return (whole*100L + part).takeIf { it in 0L..99_999_999_999L }
    }

    fun parseLabel(raw: String): VirtualChipLabel? {
        if (raw.length > 80) return null
        val token = raw.trim()
        val p = pot.matchEntire(token)
        val c = call.matchEntire(token)
        val kind = if (p != null) NumericCandidateKind.POT
                   else if (c != null) NumericCandidateKind.CALL_AMOUNT
                   else return null
        val value = parseHundredths((p ?: c)!!.groupValues[1]) ?: return null
        return VirtualChipLabel(kind, value, token)
    }
}
