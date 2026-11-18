package com.example.airemote

import java.util.ArrayDeque

/**
 * Structured READ-ONLY context from the real MediaProjection JPEG and the
 * matching ML Kit OCR. No third-party game API or automation permission is
 * inferred from it. Raw OCR text, package names, account IDs, credentials and
 * screenshots are never included in this prompt.
 */
data class LivePlayerModelContext(
    val epoch: Long,
    val frameSequence: Long,
    val capturedAtMs: Long,
    val holeCards: List<String>?,
    val boardCards: List<String>?,
    val stageHint: String?,
    val actionHints: List<String>,
    val potDisplayUnits: Long?,
    val callDisplayUnits: Long?,
    val timerSeconds: Long?,
    val ocrTwoFrameAgreement: Boolean
) {
    private fun cardsJson(cards: List<String>?): String =
        cards?.joinToString(",", "[", "]") { "\"" + it + "\"" } ?: "null"
    private fun quoted(s: String?): String = s?.let { "\"" + it + "\"" } ?: "null"

    /** Model response is diagnostic text; never a LiveCommand. */
    fun advisorPrompt(nowMs: Long, maxAgeMs: Long = 90_000L): String? {
        val age = nowMs - capturedAtMs
        if (age < 0L || age > maxAgeMs) return null
        val historical = age > 1_500L
        val actionsJson = actionHints.joinToString(",", "[", "]") { "\"" + it + "\"" }
        val packet = "{" +
            "\"schema\":\"t042-live-model-context-v1\"," +
            "\"mode\":\"READ_ONLY\"," +
            "\"game_identity_verified\":false,\"action_authorized\":false," +
            "\"historical\":" + historical + ",\"age_ms\":" + age +
            ",\"epoch\":" + epoch + ",\"frame\":" + frameSequence +
            ",\"hole_cards\":" + cardsJson(holeCards) +
            ",\"board_cards\":" + cardsJson(boardCards) +
            ",\"stage_hint\":" + quoted(stageHint) +
            ",\"action_label_hints\":" + actionsJson +
            ",\"pot_display_integer\":" + (potDisplayUnits?.toString() ?: "null") +
            ",\"call_display_integer\":" + (callDisplayUnits?.toString() ?: "null") +
            ",\"timer_seconds_hint\":" + (timerSeconds?.toString() ?: "null") +
            ",\"ocr_two_frame_agreement\":" + ocrTwoFrameAgreement + "}"
        return "T042: диагностический разбор Texas Hold'em с условными фишками. " +
            "OCR не подтверждает стол, ход или разрешение действий. " +
            "Ничего не нажимай и не предполагай отсутствующие значения. " +
            "Оцени руку и назови недостающие данные по-русски.\n" + packet
    }
}

object LivePlayerModelContextAssembler {
    const val MAX_FRAME_AGE_MS = 1_500L

    /** Cross-frame/epoch evidence is rejected. Unknown values remain null. */
    fun fromFrame(frame: FrameInfo, ocr: AutomaticOcrEvidence,
                  nowMs: Long, stopped: Boolean): LivePlayerModelContext? {
        val p = ocr.ticket
        if (stopped || frame.captureEpoch < 0L || frame.seq <= 0L ||
            frame.sourcePackage.isNullOrBlank() || frame.windowGeneration == null ||
            p.captureEpoch != frame.captureEpoch || p.frameSequence != frame.seq ||
            p.capturedAtMs != frame.capturedAtMs ||
            p.sourcePackage != frame.sourcePackage ||
            p.windowGeneration != frame.windowGeneration ||
            nowMs < frame.capturedAtMs || nowMs - frame.capturedAtMs > MAX_FRAME_AGE_MS ||
            ocr.processedAtMs < frame.capturedAtMs || ocr.processedAtMs > nowMs)
            return null

        val stable = frame.playerCardsAtCapture?.takeIf {
            it.captureEpoch == frame.captureEpoch &&
                it.capturedAtMs in (frame.capturedAtMs - 1_400L)..frame.capturedAtMs &&
                it.minimumSimilarity >= .78 && it.hole.size == 2 &&
                it.community.size in listOf(0, 3, 4, 5)
        }
        val parsed = try {
            stable?.let {
                val values = (it.hole + it.community).map { card ->
                    PokerCard.parse(card).toString()
                }
                if (values.toSet().size != values.size) null else values
            }
        } catch (_: IllegalArgumentException) { null }
        val hole = parsed?.take(2)
        val board = parsed?.drop(2)
        val stage = when (board?.size) {
            0 -> HandStage.PREFLOP
            3 -> HandStage.FLOP
            4 -> HandStage.TURN
            5 -> HandStage.RIVER
            else -> HandStage.UNKNOWN
        }.takeIf { it != HandStage.UNKNOWN && it == ocr.probableStage }?.name
        val agreed = ocr.agreement.status == OcrDiagnosticStatus.CONSISTENT_DIAGNOSTIC_ONLY &&
            ocr.interpretation.rejected.isEmpty()
        val actions = if (agreed) ocr.interpretation.actions.map { it.action.name }
            .distinct().sorted() else emptyList()
        val numbers = if (agreed) ocr.interpretation.numbers
            .groupBy { it.kind }.filterValues { it.size == 1 }
            .mapValues { it.value.single().amount } else emptyMap()
        return LivePlayerModelContext(frame.captureEpoch, frame.seq,
            frame.capturedAtMs, hole, board, stage,
            actions, numbers[NumericCandidateKind.POT],
            numbers[NumericCandidateKind.CALL_AMOUNT],
            numbers[NumericCandidateKind.TIMER_SECONDS], agreed)
    }
}

/** Bounded on-device RAM only; STOP / capture teardown clears it. */
class LivePlayerContextBuffer(private val capacity: Int = 24) {
    init { require(capacity in 1..128) }
    private val items = ArrayDeque<LivePlayerModelContext>()
    @Synchronized fun offer(sample: LivePlayerModelContext) {
        val previous = items.peekLast()
        if (previous != null &&
            (sample.epoch < previous.epoch ||
                (sample.epoch == previous.epoch && sample.frameSequence <= previous.frameSequence)))
            return
        items.addLast(sample)
        if (items.size > capacity) items.removeFirst()
    }
    @Synchronized fun latest(): LivePlayerModelContext? = items.peekLast()
    @Synchronized fun size(): Int = items.size
    @Synchronized fun clear() { items.clear() }
}
object OnDevicePlayerContextBuffer {
    val memory = LivePlayerContextBuffer()
}
