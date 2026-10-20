package com.example.airemote

import org.junit.Assert.assertEquals
import org.junit.Test

class GameReadinessJvmTest {
    private fun input() = GameReadinessInput(
        stopped = false, captureActive = true,
        appVerified = true, tableVerified = true,
        cardEpoch = 3L, turnEpoch = 3L, currentEpoch = 3L,
        cardObservedAtMs = 10_000L, turnObservedAtMs = 10_000L,
        nowMs = 10_100L, cardsConfirmed = true,
        handStageConfirmed = true, ourTurnConfirmed = true,
        exactActionLabelsConfirmed = true, strategyConnected = true)

    @Test fun readyOnlyWhenAllIndependentSourcesAgree() =
        assertEquals(GameReadinessReason.READY_FOR_STRATEGY,
            GameReadinessStatus.inspect(input()))

    @Test fun stoppedIsNeverReady() =
        assertEquals(GameReadinessReason.STOPPED,
            GameReadinessStatus.inspect(input().copy(stopped = true)))

    @Test fun unidentifiedAppIsNeverReady() =
        assertEquals(GameReadinessReason.UNKNOWN_APP,
            GameReadinessStatus.inspect(input().copy(appVerified = false)))

    @Test fun incorrectCaptureEpochIsNeverReady() =
        assertEquals(GameReadinessReason.WRONG_CAPTURE,
            GameReadinessStatus.inspect(input().copy(turnEpoch = 9L)))

    @Test fun disconnectedStrategyCannotBeSentAnything() =
        assertEquals(GameReadinessReason.STRATEGY_NOT_CONNECTED,
            GameReadinessStatus.inspect(input().copy(strategyConnected = false)))
}
