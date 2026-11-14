package com.example.airemote

import org.junit.Assert.*
import org.junit.Test

class OperatorEventTextTest {
    @Test fun everyIncidentCodeHasAReadableOperatorDescription() {
        IncidentCode.entries.forEach { code ->
            val label = OperatorEventText.russian(code)
            assertTrue(code.name, label.length >= 8)
            assertFalse(label.contains("IncidentCode"))
        }
    }

    @Test fun unsafeActionTextNeverEntersOperatorHistory() {
        val label = OperatorEventText.line(12, IncidentCode.COMMAND_REJECTED,
            "CALL Bearer-secret and player nickname")
        assertTrue(label.contains("Команда заблокирована"))
        assertFalse(label.contains("Bearer"))
        assertFalse(label.contains("nickname"))
        assertFalse(label.contains("CALL "))
        val valid = OperatorEventText.line(13, IncidentCode.COMMAND_DISPATCHED, "CHECK")
        assertTrue(valid.contains("CHECK"))
    }

    @Test fun androidGestureIsNotPresentedAsConfirmedPokerAction() {
        val os = OperatorEventText.russian(IncidentCode.GESTURE_COMPLETED_OS)
        val confirmed = OperatorEventText.russian(IncidentCode.COMMAND_CONFIRMED)
        assertTrue(os.contains("не доказательство"))
        assertNotEquals(os, confirmed)
    }

    @Test fun stopAndAmbiguousResultHaveExplicitMeanings() {
        assertTrue(OperatorEventText.russian(IncidentCode.OPERATOR_STOP).contains("STOP"))
        assertTrue(OperatorEventText.russian(IncidentCode.COMMAND_UNCERTAIN)
            .contains("повтор запрещён"))
    }
}
