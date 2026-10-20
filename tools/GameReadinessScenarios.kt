import com.example.airemote.*

object GameReadinessScenarios {
    @JvmStatic fun main(args: Array<String>) {
        val base = GameReadinessInput(false, true, true, true, 4, 4, 4, 1_000, 1_000,
            1_020, true, true, true, true, true)
        check(GameReadinessStatus.inspect(base) == GameReadinessReason.READY_FOR_STRATEGY)
        val failures = listOf(
            base.copy(stopped=true) to GameReadinessReason.STOPPED,
            base.copy(captureActive=false) to GameReadinessReason.NO_CAPTURE,
            base.copy(appVerified=false) to GameReadinessReason.UNKNOWN_APP,
            base.copy(tableVerified=false) to GameReadinessReason.UNKNOWN_TABLE,
            base.copy(turnEpoch=5) to GameReadinessReason.WRONG_CAPTURE,
            base.copy(cardObservedAtMs=-1) to GameReadinessReason.OLD_CARD_FRAME,
            base.copy(turnObservedAtMs=-1) to GameReadinessReason.OLD_TURN_FRAME,
            base.copy(cardsConfirmed=false) to GameReadinessReason.UNCONFIRMED_CARDS,
            base.copy(handStageConfirmed=false) to GameReadinessReason.UNCONFIRMED_PHASE,
            base.copy(ourTurnConfirmed=false) to GameReadinessReason.NOT_OUR_TURN,
            base.copy(exactActionLabelsConfirmed=false) to GameReadinessReason.ACTION_LABEL_UNKNOWN,
            base.copy(strategyConnected=false) to GameReadinessReason.STRATEGY_NOT_CONNECTED,
            base.copy(cardObservedAtMs=-700) to GameReadinessReason.OLD_CARD_FRAME,
            base.copy(currentEpoch=6) to GameReadinessReason.WRONG_CAPTURE,
            base.copy(nowMs=2_701) to GameReadinessReason.OLD_CARD_FRAME,
        )
        for ((input, expected) in failures) check(GameReadinessStatus.inspect(input) == expected) {
            "Expected $expected: ${GameReadinessStatus.inspect(input)}"
        }
        check(GameReadinessStatus.inspect(base.copy(nowMs=2_700)) ==
            GameReadinessReason.READY_FOR_STRATEGY)
        println("PASS: ${failures.size+2} readiness checks")
    }
}
