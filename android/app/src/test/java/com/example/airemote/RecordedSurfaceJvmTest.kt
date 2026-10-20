package com.example.airemote

import org.junit.Assert.*
import org.junit.Test

class RecordedSurfaceJvmTest {
    @Test fun navyPanelBlocksButNeutralLookNeverApprovesAction() {
        fun color(r: Int,g: Int,b: Int) = (0xff shl 24) or (r shl 16) or (g shl 8) or b
        val panel = RecordedSurfaceGate.inspect(1080,2340) { _,_ -> color(15,22,38) }
        assertEquals(RecordedSurfaceStatus.PANEL_VISIBLE,panel.status)
        val table = RecordedSurfaceGate.inspect(1080,2340) { _,_ -> color(95,97,103) }
        assertEquals(RecordedSurfaceStatus.TABLE_LIKE,table.status)
        assertFalse(table.authorizesGameAction)
        assertEquals(RecordedSurfaceStatus.UNKNOWN,
            RecordedSurfaceGate.inspect(1080,1920) { _,_ -> color(95,97,103) }.status)
    }
}
