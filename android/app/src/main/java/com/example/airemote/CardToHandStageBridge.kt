package com.example.airemote

/**
 * Bridges the two-frame, on-phone card reader to the hand-state tracker.
 * A good-looking card is not proof of the source application or table identity.
 * The candidate is diagnostic; only an independent, current source proof can
 * create a VerifiedTableObservation. No taps or networking live here.
 */
data class IndependentlyVerifiedTableSource(
    val targetPackage: String,
    val foregroundPackage: String,
    val tableKey: String,
    val captureEpoch: Long,
    val observedAtMs: Long,
    /** Only set by a genuinely checked active Accessibility window. */
    val foregroundRootChecked: Boolean,
    /** The package and table identity have been independently established. */
    val targetAndTableIdentitiesConfirmed: Boolean
)

data class CardStageAssessment(
    val probableStage: HandStage,
    val sourceConfirmed: Boolean,
    val stageConfirmed: Boolean,
    val frameSequence: Long,
    val reason: String
)

class CardToHandStageBridge(
    private val tracker: HandStateTracker,
    private val maxCardAgeMs: Long = 1_200L,
    private val maxSourceAgeMs: Long = 600L
) {
    private var lastEpoch = -1L
    private var lastSequence = -1L

    @Synchronized fun reset(reason: String = "Захват остановлен") {
        lastEpoch = -1L
        lastSequence = -1L
        tracker.reset(reason)
    }

    @Synchronized fun observe(
        reading: StableCardReading?,
        source: IndependentlyVerifiedTableSource?,
        currentEpoch: Long,
        nowMs: Long,
        operatorStopped: Boolean
    ): CardStageAssessment {
        if (operatorStopped) {
            reset("STOP: фаза раздачи недостоверна")
            return CardStageAssessment(HandStage.UNKNOWN, false, false, -1,
                "STOP: распознавание недействительно")
        }
        if (currentEpoch != lastEpoch) {
            // Never inherit a confirmed phase from a previous MediaProjection session.
            lastEpoch = currentEpoch
            lastSequence = -1L
            tracker.reset("Новая эпоха захвата")
        }
        if (reading == null) return untrusted("Нет двух согласованных кадров карт")
        if (reading.captureEpoch != currentEpoch || reading.frameSequence <= lastSequence ||
            reading.capturedAtMs < 0L || nowMs < reading.capturedAtMs ||
            nowMs - reading.capturedAtMs > maxCardAgeMs ||
            !reading.minimumSimilarity.isFinite() || reading.minimumSimilarity !in 0.78..1.0
        ) return untrusted("Повторный, устаревший или неверный результат карт")
        lastSequence = reading.frameSequence
        val all = reading.hole + reading.community
        if (reading.hole.size != 2 || reading.community.size !in listOf(0, 3, 4, 5) ||
            all.size != all.toSet().size || !all.all { CARD.matches(it) }
        ) return untrusted("Дубли или некорректный набор карт")
        val candidate = when (reading.community.size) {
            0 -> HandStage.PREFLOP
            3 -> HandStage.FLOP
            4 -> HandStage.TURN
            5 -> HandStage.RIVER
            else -> HandStage.UNKNOWN
        }
        val validSource = source != null && source.foregroundRootChecked &&
            source.targetAndTableIdentitiesConfirmed &&
            source.targetPackage.isNotBlank() &&
            source.foregroundPackage == source.targetPackage &&
            source.tableKey.isNotBlank() && source.tableKey.length <= 128 &&
            source.captureEpoch == currentEpoch && source.observedAtMs >= 0L &&
            nowMs >= source.observedAtMs && nowMs - source.observedAtMs <= maxSourceAgeMs &&
            // The source check must be contemporaneous with the image, not a future event.
            source.observedAtMs >= reading.capturedAtMs - maxSourceAgeMs
        if (!validSource) {
            tracker.invalidate("Карты распознаны, источник/стол не подтверждён")
            return CardStageAssessment(candidate, false, false, reading.frameSequence,
                "Предположительно $candidate; источник и стол ещё не подтверждены")
        }
        val verified = VerifiedTableObservation(
            tableId = source!!.tableKey,
            captureEpoch = currentEpoch,
            frameSequence = reading.frameSequence,
            capturedAtMs = reading.capturedAtMs,
            inspectedAtMs = nowMs,
            sourceVerified = true,
            recognitionConfidence = reading.minimumSimilarity,
            holeCards = reading.hole,
            communityCards = reading.community
            // The card reader cannot independently prove waiting, showdown,
            // completion, a new-hand event, or a stable hand identifier.
        )
        val tracked = tracker.ingest(verified)
        return CardStageAssessment(candidate, true, tracked.trustworthyNow,
            reading.frameSequence, tracked.reason)
    }

    private fun untrusted(reason: String): CardStageAssessment {
        tracker.invalidate(reason)
        return CardStageAssessment(HandStage.UNKNOWN, false, false, lastSequence, reason)
    }

    private companion object { val CARD = Regex("^[2-9TJQKA][cdhs]$") }
}

/** Single stage bridge tied to the process lifetime. UI statuses do not authorize actions. */
object OnDeviceStageBridge {
    val engine = CardToHandStageBridge(OnDeviceHandState.tracker)
    @Volatile var lastAssessment: CardStageAssessment? = null
    fun reset(reason: String) {
        engine.reset(reason)
        lastAssessment = null
    }
}
