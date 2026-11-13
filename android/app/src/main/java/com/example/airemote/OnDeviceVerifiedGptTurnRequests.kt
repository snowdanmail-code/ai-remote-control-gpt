package com.example.airemote

import android.content.Context

/**
 * Requests exactly one GPT decision for each authenticated turn identity.
 * Never infers a turn from OCR, a visible card, or an Android package name.
 * A later independently verified frame must still consume the proposal through
 * the normal action loop; the cloud callback NEVER sends a gesture.
 */
object OnDeviceVerifiedGptTurnRequests {
    private data class TurnKey(
        val tableId: String, val handId: String, val turnId: String,
        val captureEpoch: Long, val windowGeneration: Long
    )

    private var requested: TurnKey? = null
    @Volatile private var lastReason = "GPT: ожидание подтверждённого хода"

    @Synchronized fun reset() {
        requested = null
        lastReason = "GPT: сеанс остановлен"
    }

    fun status(): String = lastReason

    @Synchronized fun schedule(
        context: Context,
        observation: AutonomousObservation,
        cards: StableCardReading?
    ): Boolean {
        if (cards == null || CaptureObservationMode.isSpectator() ||
            OperatorStopStore.isStopped(context) || !ChatGptCloudBridge.planUsageAuthorized(context) ||
            !observation.tableHandIndependentlyVerified ||
            !observation.windowIndependentlyVerified) return false
        val key = TurnKey(observation.tableId, observation.handId,
            observation.turnId, observation.view.captureEpoch, observation.windowGeneration)
        if (key == requested) return false
        val profiles = OnDeviceOpponentAwareness.verifiedProfilesForTurn()
        // Session popup metrics are read-only and never a verified identity
        // or proof of a permitted wager.
        val sessionStats = OnDeviceOneWinPostSeatStudy.advisoryFor(observation)
        val handSeats = OnDeviceOneWinPostSeatStudy.verifiedHandSeatSummary(observation)
        requested = key
        lastReason = "GPT: анализ подтверждённого хода и соперников"
        ChatGptCloudBridge.askVerifiedTurnDecision(
            context.applicationContext, observation, cards, profiles,
            sessionStats, handSeats
        ) { result ->
            synchronized(this) {
                if (requested == key)
                    lastReason = if (result.accepted)
                        "GPT: кандидат сохранён; ожидается новый проверенный кадр"
                    else "GPT: ${result.reason}"
            }
        }
        return true
    }
}
