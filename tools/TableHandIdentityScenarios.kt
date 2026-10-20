import com.example.airemote.*

/** Pure JVM regression tests for fail-closed table/hand proof binding. */
object TableHandIdentityScenarios {
    @JvmStatic fun main(args: Array<String>) {
        var n = 0
        fun checkCase(ok: Boolean, description: String) { check(ok) { description }; n++ }
        fun a(table: String = "table-123", hand: String = "hand-456", epoch: Long = 7,
              pkg: String = "com.external.test", generation: Long = 3,
              issued: Long = 900, expires: Long = 4000, verified: Boolean = true) =
            IndependentTableHandAssertion(table, hand, epoch, pkg, generation, issued, expires, verified)
        fun t(frame: Long = 1, at: Long = 1000, ep: Long = 7,
              pkg: String = "com.external.test", gen: Long = 3) = OcrFrameTicket(ep, frame, at, pkg, gen)
        val waiting = OcrDiagnosticResult(OcrDiagnosticStatus.WAITING_SECOND_FRAME, "waiting")
        val consistent = OcrDiagnosticResult(OcrDiagnosticStatus.CONSISTENT_DIAGNOSTIC_ONLY, "consistent")
        val rejected = OcrDiagnosticResult(OcrDiagnosticStatus.REJECTED, "rejected")
        fun inspect(guard: TableHandIdentityGuard = TableHandIdentityGuard(),
                    identity: IndependentTableHandAssertion? = a(),
                    ticket: OcrFrameTicket? = t(),
                    diag: OcrDiagnosticResult = waiting,
                    now: Long = 1100, stop: Boolean = false) =
            guard.inspect(identity, ticket, diag, now, stop).status
        val g = TableHandIdentityGuard()
        checkCase(inspect(g, null) == TableHandBindingStatus.UNVERIFIED_IDENTITY, "No independent proof")
        checkCase(inspect(g, a(verified=false)) == TableHandBindingStatus.UNVERIFIED_IDENTITY, "Unverified identity")
        checkCase(inspect(g) == TableHandBindingStatus.WAITING_INDEPENDENT_FRAME, "First independently attested frame")
        checkCase(inspect(g, a(issued=1500), t(frame=2,at=1600), consistent, now=1650) == TableHandBindingStatus.MATCHED_DIAGNOSTIC_ONLY,
            "Two correct frames remain only diagnostic")
        checkCase(inspect(g, a(issued=1700), t(frame=3,at=1800), consistent, now=1850) == TableHandBindingStatus.MATCHED_DIAGNOSTIC_ONLY,
            "Third independent frame")
        checkCase(inspect(g, a(issued=1900), t(frame=4,at=2000), waiting, now=2050) == TableHandBindingStatus.WAITING_INDEPENDENT_FRAME,
            "OCR changed never confirms")
        checkCase(inspect(g, a(issued=2100), t(frame=5,at=2200), consistent, now=2250) == TableHandBindingStatus.MATCHED_DIAGNOSTIC_ONLY,
            "Fresh OCR match can restore diagnostic only")
        checkCase(inspect(g, a(hand="different", issued=2300), t(frame=6,at=2400), consistent, now=2450) == TableHandBindingStatus.REJECTED,
            "Hand change immediately revokes")
        checkCase(inspect(g, a(hand="different", issued=2400), t(frame=7,at=2500), consistent, now=2550) == TableHandBindingStatus.WAITING_INDEPENDENT_FRAME,
            "After hand switch new first proof")
        checkCase(inspect(g, a(hand="different", issued=2600), t(frame=8,at=2700), consistent, now=2750) == TableHandBindingStatus.MATCHED_DIAGNOSTIC_ONLY,
            "New hand takes two frames")
        checkCase(inspect(g, a(table="other", issued=2800), t(frame=9,at=2900), consistent, now=2950) == TableHandBindingStatus.REJECTED,
            "Table switch revokes")
        val mismatch = TableHandIdentityGuard()
        checkCase(inspect(mismatch, a(), t(ep=8)) == TableHandBindingStatus.REJECTED, "Epoch mismatch")
        checkCase(inspect(mismatch, a(), t(gen=4)) == TableHandBindingStatus.REJECTED, "Window mismatch")
        checkCase(inspect(mismatch, a(), t(pkg="com.other.test")) == TableHandBindingStatus.REJECTED, "Package mismatch")
        checkCase(inspect(mismatch, a(table=" ")) == TableHandBindingStatus.REJECTED, "Blank table")
        checkCase(inspect(mismatch, a(hand="")) == TableHandBindingStatus.REJECTED, "Blank hand")
        checkCase(inspect(mismatch, a(table="a".repeat(129))) == TableHandBindingStatus.REJECTED, "Oversized table")
        checkCase(inspect(mismatch, a(hand="x;y")) == TableHandBindingStatus.REJECTED, "Malformed hand")
        checkCase(inspect(mismatch, a(pkg="untrusted"),t(pkg="untrusted")) == TableHandBindingStatus.REJECTED, "Invalid package")
        checkCase(inspect(mismatch, a(expires=900)) == TableHandBindingStatus.REJECTED, "Bad expiration")
        checkCase(inspect(mismatch, a(issued=-1)) == TableHandBindingStatus.REJECTED, "Negative issuance")
        checkCase(inspect(mismatch, a(issued=1100)) == TableHandBindingStatus.REJECTED, "Future issuance")
        checkCase(inspect(mismatch, a(expires=950)) == TableHandBindingStatus.REJECTED, "Expired at frame")
        checkCase(inspect(mismatch, a(expires=1050),now=1100) == TableHandBindingStatus.REJECTED, "Expired at callback")
        checkCase(inspect(mismatch, a(),now=3000) == TableHandBindingStatus.REJECTED, "Stale frame")
        checkCase(inspect(mismatch, a(),now=900) == TableHandBindingStatus.REJECTED, "Future frame")
        checkCase(inspect(mismatch, a(),null) == TableHandBindingStatus.REJECTED, "Missing ticket")
        checkCase(inspect(mismatch, a(),t(frame=0)) == TableHandBindingStatus.REJECTED, "Invalid frame number")
        checkCase(inspect(mismatch, a(),t(at=-1)) == TableHandBindingStatus.REJECTED, "Invalid frame time")
        checkCase(inspect(mismatch, a(),t(ep=-1), now=1100) == TableHandBindingStatus.REJECTED, "Bad epoch")
        checkCase(inspect(mismatch, a(),t(gen=-1)) == TableHandBindingStatus.REJECTED, "Bad window generation")
        checkCase(inspect(mismatch, a(),t(),rejected) == TableHandBindingStatus.REJECTED, "OCR reject")
        val ordered = TableHandIdentityGuard()
        inspect(ordered)
        checkCase(inspect(ordered,a(issued=950),t(frame=1,at=1200),consistent,now=1250) == TableHandBindingStatus.REJECTED,
            "Repeated frame sequence")
        checkCase(inspect(ordered,a(issued=1250),t(frame=2,at=1300),consistent,now=1350) == TableHandBindingStatus.WAITING_INDEPENDENT_FRAME,
            "No implicit reseed on repeat")
        checkCase(inspect(ordered,a(issued=1250),t(frame=3,at=1300),consistent,now=1350) == TableHandBindingStatus.REJECTED,
            "Repeated frame timestamp")
        val replayedProof = TableHandIdentityGuard()
        inspect(replayedProof)
        checkCase(inspect(replayedProof,a(issued=900),t(frame=2,at=1200),consistent,now=1250) == TableHandBindingStatus.REJECTED,
            "Same attestation timestamp cannot certify two frames")
        checkCase(inspect(replayedProof,a(issued=1100),t(frame=3,at=1200),consistent,now=1250) == TableHandBindingStatus.WAITING_INDEPENDENT_FRAME,
            "Proof replay is not reused as a seed")
        val expiresBetween = TableHandIdentityGuard()
        inspect(expiresBetween,a(expires=1150))
        checkCase(inspect(expiresBetween,a(issued=1200,expires=4000),t(frame=2,at=1300),consistent,now=1350) == TableHandBindingStatus.REJECTED,
            "Old independent assertion expired between frames")
        val gap = TableHandIdentityGuard()
        inspect(gap)
        checkCase(inspect(gap,a(issued=3100,expires=5000),t(frame=2,at=3200),consistent,now=3250) == TableHandBindingStatus.REJECTED,
            "Long inter-frame gap")
        val issuer = TableHandIdentityGuard()
        inspect(issuer)
        checkCase(inspect(issuer,a(issued=890),t(frame=2,at=1600),consistent,now=1650) == TableHandBindingStatus.REJECTED,
            "Issuance time goes backwards")
        val stopGuard = TableHandIdentityGuard()
        inspect(stopGuard)
        checkCase(inspect(stopGuard, a(), t(frame=2,at=1200),consistent, now=1250,stop=true) == TableHandBindingStatus.REJECTED,
            "STOP always revokes")
        checkCase(inspect(stopGuard,a(issued=1300),t(frame=3,at=1400),consistent,now=1450) == TableHandBindingStatus.WAITING_INDEPENDENT_FRAME,
            "No inheritance after STOP")
        stopGuard.reset()
        checkCase(inspect(stopGuard,a(issued=1500),t(frame=4,at=1600),consistent,now=1650) == TableHandBindingStatus.WAITING_INDEPENDENT_FRAME,
            "Explicit reset")
        checkCase(inspect(TableHandIdentityGuard(), a(), t(), consistent) == TableHandBindingStatus.WAITING_INDEPENDENT_FRAME,
            "OCR consensus alone cannot establish hand identity")
        println("TableHandIdentityScenarios: $n checks passed")
    }
}
