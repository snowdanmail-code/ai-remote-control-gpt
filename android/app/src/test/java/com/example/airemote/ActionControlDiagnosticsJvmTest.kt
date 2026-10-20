package com.example.airemote

import org.junit.Assert.*
import org.junit.Test

/** These Android Gradle/JUnit tests run only when Android SDK is available. */
class ActionControlDiagnosticsJvmTest {
    private fun box(l: Int,t: Int,r: Int,b: Int) = NormalizedTextBox(l,t,r,b)
    private val pkg = "com.example.pokertest"
    private fun profile() = RealFrameCalibration("manual",pkg,1080,2340,listOf(
        RealFrameZone(RealFieldRole.SEAT_ACTION,box(50,90,320,200),0),
        RealFrameZone(RealFieldRole.SEAT_ACTION,box(670,90,950,200),1),
        RealFrameZone(RealFieldRole.HERO_FOLD,box(40,920,300,975)),
        RealFrameZone(RealFieldRole.HERO_CHECK_CALL,box(350,920,645,975)),
        RealFrameZone(RealFieldRole.HERO_BET_RAISE,box(690,920,960,975))))
    private fun ticket(id: Long,time: Long) = OcrFrameTicket(1,id,time,pkg,3)
    private fun turn(time: Long) = ConfirmedTurnDisplay(TurnDisplayState.READY,
        setOf(VisibleButtonRole.RED_FOLD,VisibleButtonRole.GREEN_CHECK_OR_CALL,
            VisibleButtonRole.GOLD_BET_OR_RAISE),time,1,1,false)
    private val words = listOf(
        PositionedOcrLine("Fold",box(100,932,180,955)),
        PositionedOcrLine("Check",box(415,932,505,955)),
        PositionedOcrLine("Bet",box(765,932,835,955)))
    @Test fun twoFramesStayDiagnostic() {
        val gate = ActionControlDiagnostics()
        assertTrue(gate.configure(profile()))
        assertEquals(ActionStripStatus.COLLECTING,
            gate.inspect(ticket(1,1000),1080,2340,1050,words,turn(1000),false).status)
        val stable = gate.inspect(ticket(2,1300),1080,2340,1350,words,turn(1300),false)
        assertEquals(ActionStripStatus.STABLE_DIAGNOSTIC,stable.status)
        assertEquals(3,stable.stable.size)
        assertFalse(stable.authorizesTap)
        assertFalse(stable.confirmsHandOrTurn)
    }
    @Test fun stopErasesAndRejects() {
        val gate = ActionControlDiagnostics()
        assertTrue(gate.configure(profile()))
        assertEquals(ActionStripStatus.REJECTED,
            gate.inspect(ticket(1,1000),1080,2340,1100,words,turn(1000),true).status)
        gate.clear()
        assertFalse(gate.configured())
    }
}
