package com.example.airemote

import java.util.Base64
import kotlin.math.abs
import kotlin.math.sqrt

/** OCR can never see these symbols: 1win uses a WHITE down-arrow in a dark circular vacant-seat marker. */
data class OneWinVacantSeatMarker(
    val bounds: NormalizedTextBox,
    val confidence: Double
)

/**
 * Read-only grayscale template correlation, sampled at reference 354px width.
 * The small 15x20 icon-only reference is extracted from the owner's 13-Nov screenshots
 * (no players, names, balances or identifiable screenshot pixels embedded).
 * Correlation finds the glyph across the table; it does not turn coordinates
 * into an authorized touch or assume that the lower slot is already HERO.
 */
object OneWinFreeSeatArrowDetector {
    private const val REF_W = 15
    private const val REF_H = 20
    private const val REFERENCE_WIDTH = 354
    private const val THRESHOLD = 0.87
    private val reference = Base64.getDecoder().decode(
        "Oz1CRUVIS0xMSUdESj5BO0NEREhNTkhMSklFSEBAO0FERkdKUExNTUlDRUI8O0FEREVNSkpMTUVDRUI8OT9CREJFaq5bRkVBQ0A6OD5BP0NGh/9zPUhAQj85ODw/Oz9Gg/huOkZAQD05Njs9PkxQf+VrWkw5Pjs3NDg7OXqnq96ZuV0/PDk1MTY4OUaj28PQmDc7OTYyMDQ2NjREj8SKPDk0NzUwLzMzNTQtOk09NDIxNTMuLTAwNEdXU1BRWEkvMzAtKy0sN2J2cXdxb2Q6MC4sKSopKy8wMC8vOC8pLSwrJywoJikqLC0sKyssLikoKSssKioqKywpLS4sKioxNDImJSoqKiktLSsoKDFDO0M1LCwqKSgsJyguO0BJPVBGPzkzMDA3OjxHUkpG")
        .map { (it.toInt() and 255).toDouble() }
    private val referenceMean = reference.average()
    private val referenceEnergy = reference.sumOf { (it-referenceMean)*(it-referenceMean) }

    /** Input is ARGB pixels of a source image resized to 354px across. */
    fun detect(argb: IntArray, width: Int, height: Int): List<OneWinVacantSeatMarker> {
        if (width != REFERENCE_WIDTH || height !in 650..920 ||
            argb.size != width * height) return emptyList()
        val gray = IntArray(argb.size) { i ->
            val pixel = argb[i]
            ((pixel ushr 16 and 255) * 299 +
                (pixel ushr 8 and 255) * 587 +
                (pixel and 255) * 114) / 1000
        }
        data class Match(val x: Int, val y: Int, val score: Double)
        val raw = mutableListOf<Match>()
        for (y in 90..minOf(height - 45, (height * 84 / 100)) step 2) {
            for (x in 21 until width - 21 step 2) {
                if (gray[y * width + x] < 120) continue
                var total = 0.0
                var totalSq = 0.0
                var cross = 0.0
                var n = 0
                for (row in 0 until REF_H) {
                    val yy = y - 15 + ((row * 2 + 1) * 36 / (2 * REF_H))
                    for (col in 0 until REF_W) {
                        val xx = x - 13 + ((col * 2 + 1) * 27 / (2 * REF_W))
                        val sample = gray[yy * width + xx].toDouble()
                        total += sample
                        totalSq += sample * sample
                        cross += sample * reference[n++]
                    }
                }
                val mean = total / reference.size
                val variance = totalSq - reference.size * mean * mean
                if (variance <= 1.0) continue
                val score = (cross - reference.size * mean * referenceMean) /
                    sqrt(variance * referenceEnergy)
                if (score >= THRESHOLD) raw.add(Match(x, y, score))
            }
        }
        val accepted = mutableListOf<Match>()
        for (candidate in raw.sortedByDescending { it.score }) {
            if (accepted.none { abs(it.x - candidate.x) <= 28 &&
                    abs(it.y - candidate.y) <= 35 })
                accepted.add(candidate)
            if (accepted.size == 10) break
        }
        return accepted.sortedWith(compareBy<Match> { it.y }.thenBy { it.x })
            .map { m ->
                // Tight icon rectangle; NOT a touch coordinate or gameplay authorization.
                OneWinVacantSeatMarker(
                    NormalizedTextBox(
                        ((m.x - 20).coerceAtLeast(0) * 1000 / width),
                        ((m.y - 20).coerceAtLeast(0) * 1000 / height),
                        ((m.x + 20).coerceAtMost(width - 1) * 1000 / width),
                        ((m.y + 20).coerceAtMost(height - 1) * 1000 / height)),
                    m.score.coerceIn(0.0, 1.0))
            }
    }
}

/** Two independent, fresh frames: no seat indexes or hero identity are inferred. */
class OneWinFreeSeatAgreement {
    private var last: OcrFrameTicket? = null
    private var positions = emptyList<OneWinVacantSeatMarker>()
    fun reset() { last = null; positions = emptyList() }

    @Synchronized fun observe(ticket: OcrFrameTicket,
        markers: List<OneWinVacantSeatMarker>): List<OneWinVacantSeatMarker> {
        val prior = last
        val clean = markers.filter { it.confidence.isFinite() &&
            it.confidence >= 0.87 && it.confidence <= 1.000001 &&
            it.bounds.valid() }.sortedBy { it.bounds.centerY() * 1000 + it.bounds.centerX() }
        val stable = prior != null && prior.sourcePackage == ticket.sourcePackage &&
            prior.captureEpoch == ticket.captureEpoch &&
            prior.windowGeneration == ticket.windowGeneration &&
            ticket.frameSequence > prior.frameSequence &&
            ticket.capturedAtMs > prior.capturedAtMs &&
            ticket.capturedAtMs - prior.capturedAtMs in 250L..5_000L &&
            clean.isNotEmpty() && clean.size == positions.size &&
            clean.indices.all {
                abs(clean[it].bounds.centerX() - positions[it].bounds.centerX()) <= 18 &&
                abs(clean[it].bounds.centerY() - positions[it].bounds.centerY()) <= 18
            }
        last = ticket
        positions = clean
        return if (stable) clean else emptyList()
    }
}

enum class OneWinBuyInReadingStatus { NOT_DIALOG, INCOMPLETE, VERIFIED }
data class OneWinBuyInDialogReading(
    val status: OneWinBuyInReadingStatus,
    val smallBlindCents: Long? = null,
    val bigBlindCents: Long? = null,
    val availableCents: Long? = null,
    val debitCents: Long? = null,
    val minimumCents: Long? = null,
    val maximumCents: Long? = null,
    val inputCents: Long? = null,
    val okBox: NormalizedTextBox? = null,
    val autoRebuyControlVisible: Boolean = false,
    val reason: String = ""
) {
    /** Pure validation; this property NEVER approves transfer of funds. */
    fun withinLimits(limits: OneWinCashTableLimits?): Boolean =
        status == OneWinBuyInReadingStatus.VERIFIED && limits?.valid() == true &&
            smallBlindCents != null && bigBlindCents != null &&
            bigBlindCents <= limits.maxBigBlindCents &&
            minimumCents != null && maximumCents != null &&
            inputCents != null && debitCents == inputCents &&
            inputCents in minimumCents..maximumCents &&
            inputCents <= limits.maxBuyInCents &&
            availableCents != null && availableCents >= inputCents
}

object OneWinBuyInDialogParser {
    private val moneyToken = Regex("""(?iu)(\d{1,6}(?:[.,]\d{1,2})?)\s*\$""")
    private val blinds = Regex(
        """(?iu)(\d{1,5}(?:[.,]\d{1,2})?)\s*\$?\s*/\s*(\d{1,5}(?:[.,]\d{1,2})?)\s*\$""")
    private fun norm(raw: String): String = raw.lowercase()
        .replace('ё','е').replace(Regex("""\s+"""), " ").trim()
    /** Pure Kotlin amount parsing; JVM scenario builds do not link Android preferences. */
    private fun cents(raw: String): Long? {
        val txt = raw.trim().replace(',', '.')
        if (!Regex("""[0-9]{1,6}(?:\.[0-9]{1,2})?""").matches(txt)) return null
        val parts = txt.split('.')
        val major = parts[0].toLongOrNull() ?: return null
        val minor = when (parts.getOrNull(1)?.length) {
            null -> 0L
            1 -> parts[1].toLongOrNull()?.times(10L)
            2 -> parts[1].toLongOrNull()
            else -> null
        } ?: return null
        return (major * 100L + minor).takeIf { it in 0L..100_000_000L }
    }
    private fun values(text: String) = moneyToken.findAll(text)
        .mapNotNull { cents(it.groupValues[1]) }.toList()
    private fun parseMoneyBand(lines: List<PositionedOcrLine>, lower: Int, upper: Int,
                               keyword: Regex): Long? {
        val row = lines.filter { it.box.centerY() in lower..upper }
        val matching = row.filter { keyword.containsMatchIn(norm(it.text)) }
        if (matching.isEmpty()) return null
        val joined = row.sortedBy { it.box.left }.joinToString(" ") { it.text }
        return values(joined).singleOrNull()
    }
    fun read(lines: List<PositionedOcrLine>): OneWinBuyInDialogReading {
        val valid = lines.filter { it.box.valid() && it.text.isNotBlank() }
        val hasHeader = valid.any {
            it.box.centerY() in 48..128 &&
                Regex("""(?iu)бай\s*[-–]?\s*ин|buy\s*[- ]?in""")
                    .containsMatchIn(norm(it.text))
        }
        if (!hasHeader) return OneWinBuyInDialogReading(OneWinBuyInReadingStatus.NOT_DIALOG)
        val variant = valid.filter { it.box.centerY() in 105..178 }
            .joinToString(" ") { it.text }
        val match = blinds.find(variant)
        val pair = if (variant.contains("холдем", true) || variant.contains("holdem", true))
            match?.let { cents(it.groupValues[1]) to cents(it.groupValues[2]) }
        else null
        val balance = parseMoneyBand(valid, 165, 225,
            Regex("""доступно\s+средств|available\s+(funds|balance)"""))
        val debit = parseMoneyBand(valid, 216, 279,
            Regex("""будет\s+списано|will\s+be\s+debited"""))
        val buttons = valid.filter { it.box.centerY() in 277..389 }
        fun labeledValue(re: Regex, xRange: IntRange): Long? {
            val grouped = buttons.filter { it.box.centerX() in xRange }
            // Labels and amounts may occupy separate OCR text lines.
            if (grouped.none { re.containsMatchIn(norm(it.text)) }) return null
            return grouped.flatMap { values(it.text) }.distinct().singleOrNull()
        }
        val minimum = labeledValue(Regex("""^мин\b|^min\b"""), 30..340)
        val maximum = labeledValue(Regex("""^макс\b|^max\b"""), 680..980)
        val input = buttons.filter { it.box.centerX() in 350..675 &&
            !norm(it.text).contains("мин") && !norm(it.text).contains("макс") }
            .flatMap { values(it.text) }.singleOrNull()
        val ok = valid.filter { it.box.centerY() in 916..991 &&
            it.box.centerX() in 350..680 &&
            norm(it.text) in setOf("ok","ок") }.singleOrNull()?.box
        val rebuy = valid.any { it.box.centerY() in 388..525 &&
            (norm(it.text).contains("авто ребай") ||
             norm(it.text).contains("авто бай-ин")) }
        val small = pair?.first
        val big = pair?.second
        val good = small != null && big != null && small > 0L && big > small &&
            balance != null && debit != null && minimum != null && maximum != null &&
            input != null && ok != null && minimum > 0L &&
            maximum >= minimum && input in minimum..maximum &&
            debit == input && balance >= debit
        return OneWinBuyInDialogReading(
            if (good) OneWinBuyInReadingStatus.VERIFIED
                else OneWinBuyInReadingStatus.INCOMPLETE,
            small, big, balance, debit, minimum, maximum, input, ok, rebuy,
            if (good) "Суммы и элементы окна согласованы; средства НЕ переводились"
                else "Требуется повторная проверка текста и сумм бай-ина")
    }
}

/** A matching second OCR snapshot is only evidence of the open dialog. */
class OneWinBuyInAgreement {
    private var prior: Pair<OcrFrameTicket, OneWinBuyInDialogReading>? = null
    fun reset() { prior = null }

    @Synchronized fun observe(ticket: OcrFrameTicket,
        candidate: OneWinBuyInDialogReading): OneWinBuyInDialogReading? {
        val old = prior
        prior = ticket to candidate
        if (candidate.status != OneWinBuyInReadingStatus.VERIFIED || old == null ||
            old.second != candidate) return null
        val t = old.first
        return candidate.takeIf { t.sourcePackage == ticket.sourcePackage &&
            t.captureEpoch == ticket.captureEpoch &&
            t.windowGeneration == ticket.windowGeneration &&
            ticket.frameSequence > t.frameSequence &&
            ticket.capturedAtMs > t.capturedAtMs &&
            ticket.capturedAtMs - t.capturedAtMs in 250L..6_000L }
    }
}