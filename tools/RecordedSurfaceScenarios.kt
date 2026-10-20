package com.example.airemote

/** Defensive RGB probes, not claims of in-app accuracy or tap authorization. */
object RecordedSurfaceScenarios {
    @JvmStatic fun main(args: Array<String>) {
        var count = 0
        fun checkCase(ok: Boolean, name: String) { count++; check(ok) { name } }
        fun solid(r: Int, g: Int, b: Int): (Int, Int) -> Int =
            { _, _ -> (0xff shl 24) or (r shl 16) or (g shl 8) or b }
        val clean = RecordedSurfaceGate.inspect(1080, 2340, solid(95, 97, 103))
        checkCase(clean.status == RecordedSurfaceStatus.TABLE_LIKE, "neutral recorded felt")
        checkCase(!clean.authorizesGameAction, "neutral observation never authorizes")
        val overlay = RecordedSurfaceGate.inspect(1080, 2340, solid(15, 22, 38))
        checkCase(overlay.status == RecordedSurfaceStatus.PANEL_VISIBLE, "navy history/stats overlay")
        checkCase(!overlay.authorizesGameAction, "overlay cannot authorize")
        checkCase(RecordedSurfaceGate.inspect(540, 1170, solid(15, 22, 38)).status ==
            RecordedSurfaceStatus.PANEL_VISIBLE, "scaled capture")
        checkCase(RecordedSurfaceGate.inspect(1080, 1920, solid(95,97,103)).status ==
            RecordedSurfaceStatus.UNKNOWN, "unmeasured aspect ratio")
        checkCase(RecordedSurfaceGate.inspect(1080, 2340, solid(0,0,0)).status ==
            RecordedSurfaceStatus.UNKNOWN, "black transition frame")
        checkCase(RecordedSurfaceGate.inspect(1080, 2340, solid(255,255,255)).status ==
            RecordedSurfaceStatus.UNKNOWN, "blank bright dialog")
        // A partial right-side panel must never be mistaken for a confirmed table.
        val ambiguous = RecordedSurfaceGate.inspect(1080, 2340) { x,y ->
            if (x > 700 && y > 800) (0xff shl 24) or (15 shl 16) or (22 shl 8) or 38
            else (0xff shl 24) or (95 shl 16) or (97 shl 8) or 103
        }
        checkCase(ambiguous.status != RecordedSurfaceStatus.TABLE_LIKE,
            "partial overlay never accepted as table-like")
        println("Recorded surface gate scenarios: $count passed")
    }
}
