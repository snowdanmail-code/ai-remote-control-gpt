package com.example.airemote

/** Pure JVM checks: independent root evidence, cross-frame stability and revocation. */
object WindowOriginScenarios {
    @JvmStatic fun main(args: Array<String>) {
        var passed = 0
        fun check(ok: Boolean, label: String) { require(ok) { label }; passed++ }
        fun root(pkg: String = "example.external.app", at: Long = 1000L, generation: Long = 3) =
            RootWindowEvidence(pkg, at, generation)
        fun sample(epoch: Long = 1L, seq: Long = 1L, at: Long = 1100L,
                   source: RootWindowEvidence? = root()) =
            CapturedWindowFrame(epoch, seq, at, source)
        fun fresh() = WindowOriginTracker("com.example.airemote")
        val t = fresh()
        val a = t.observe(sample())
        check(a.status == WindowOriginStatus.UNKNOWN, "First frame alone is not stable")
        val b = t.observe(sample(seq=2, at=1450, source=root(at=1350)))
        check(b.safeForLocalDiagnostics, "Two distinct frames of same root stabilize")
        check(b.observedPackage == "example.external.app", "Package diagnostic")
        check(b.reason.contains("НЕ подтверждён"), "Does not certify a table")
        check(t.stillSameRoot(b, root(at=1350), root(at=1470), 1500), "Same active root")
        check(!t.stillSameRoot(b, root(at=1350), root(generation=4, at=1470), 1500), "Window generation changed")
        check(!t.stillSameRoot(b, root(at=1350), root(at=1470), 2300), "After processing stale")
        check(!t.stillSameRoot(b, null, root(), 1500), "Missing initial proof")
        check(!t.stillSameRoot(a, root(), root(), 1300), "First frame cannot publish")
        val c = t.observe(sample(seq=3, at=1750, source=root("example.external.other", 1700, 4)))
        check(!c.safeForLocalDiagnostics, "Switching applications loses status")
        check(!t.observe(sample(seq=4, at=1800, source=null)).safeForLocalDiagnostics, "Missing root")
        check(!t.observe(sample(seq=5, at=2000, source=root("com.example.airemote", 1950))).safeForLocalDiagnostics, "Own UI")
        check(!t.observe(sample(seq=6, at=2300, source=root("com.android.systemui", 2250))).safeForLocalDiagnostics, "System UI")
        check(!t.observe(sample(seq=7, at=2600, source=root("com.google.android.permissioncontroller", 2550))).safeForLocalDiagnostics, "Permission dialog")
        check(!t.observe(sample(seq=8, at=3100, source=root(at=2300))).safeForLocalDiagnostics, "Old foreground sample")
        check(!t.observe(sample(seq=9, at=3000, source=root(at=3100))).safeForLocalDiagnostics, "Future foreground sample")
        check(!t.observe(sample(seq=10, at=3000, source=root("invalid package", 2900))).safeForLocalDiagnostics, "Malformed package")
        val same = fresh()
        same.observe(sample())
        check(!same.observe(sample(seq=1, at=1400, source=root(at=1300))).safeForLocalDiagnostics, "Duplicate frame")
        same.observe(sample(seq=2, at=1500, source=root(at=1450)))
        check(same.observe(sample(seq=3, at=1600, source=root(at=1550))).safeForLocalDiagnostics, "New independent two frames")
        check(!same.observe(sample(epoch=2, seq=4, at=1800, source=root(at=1750))).safeForLocalDiagnostics, "Epoch reset")
        val slow = fresh()
        slow.observe(sample())
        check(!slow.observe(sample(seq=2, at=3200, source=root(at=3150))).safeForLocalDiagnostics, "Long gap resets")
        val window = fresh()
        window.observe(sample())
        check(!window.observe(sample(seq=2, at=1450, source=root(at=1380, generation=5))).safeForLocalDiagnostics, "Window change even within same package")
        val ordered = fresh()
        ordered.observe(sample())
        check(!ordered.observe(sample(seq=2, at=1000, source=root(at=950))).safeForLocalDiagnostics, "Time regression")
        t.reset()
        check(!t.observe(sample(seq=9, at=3000, source=root(at=2970))).safeForLocalDiagnostics, "Explicit reset")
        check(passed == 24, "Unexpected test count $passed")
        println("WindowOriginScenarios: $passed OK")
    }
}
