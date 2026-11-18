package com.example.airemote

import java.security.KeyPairGenerator
import java.security.Signature

/** Test-only signed provider. Private key never enters an Android build. */
object SignedPokerProviderScenarios {
    @JvmStatic fun main(args:Array<String>) {
        var n=0
        fun ok(result:Boolean,name:String){check(result){name};n++}
        val pair=KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val verifier=Ed25519PokerPacketVerifier(pair.public.encoded)
        val p=ExternalTurnAttestation("authorized-fixture","proof-a",
            "table-1","hand-1","turn-1","com.virtual.test",7,5,13,1000,1700)
        val eq=FrameBoundEquity("hand-1","turn-1",7,13,.76,300,true,
            provenance="PROVIDER_SIGNED_EQUITY")
        val state=IndependentlyAuthenticatedPokerFrame(p,HandStage.FLOP,true,
            setOf(LiveAction.FOLD,LiveAction.CALL,LiveAction.RAISE),
            120,2,4500,listOf("Ah","Kd"),listOf("3c","9s","Jh"),
            "fingerprint-unique",selectedBetHundredths=50,minBetHundredths=20,
            maxBetHundredths=100,equity=eq,verifiedActiveOpponentCount=2)
        fun sign(state:IndependentlyAuthenticatedPokerFrame): SignedPokerProviderFrame {
            val signer=Signature.getInstance("Ed25519")
            signer.initSign(pair.private)
            signer.update(SignedPokerFrameEncoding.encode(state))
            return SignedPokerProviderFrame(state,signer.sign())
        }
        val good=sign(state)
        ok(verifier.verify(good),"provider signature validates full state")
        ok(!verifier.verify(good.copy(content=state.copy(potHundredths=121))),
            "pot tampering invalidates signature")
        ok(!verifier.verify(good.copy(content=state.copy(callHundredths=0))),
            "call tampering invalidates signature")
        ok(!verifier.verify(good.copy(content=state.copy(
            legalActions=setOf(LiveAction.CHECK)))),
            "legal actions cannot be replaced under same proof")
        ok(!verifier.verify(good.copy(content=state.copy(
            ownHoleCards=listOf("Ah","Ks")))),
            "own cards cannot be replaced under same proof")
        ok(!verifier.verify(good.copy(content=state.copy(
            publicCards=listOf("3c","9s","Th")))),
            "public board cannot be replaced")
        ok(!verifier.verify(good.copy(content=state.copy(
            attestation=p.copy(turnId="turn-2")))),
            "turn id cannot be replaced")
        ok(!verifier.verify(good.copy(content=state.copy(
            selectedBetHundredths=99))),
            "selected bet cannot be changed")
        ok(!verifier.verify(good.copy(content=state.copy(
            verifiedActiveOpponentCount=3))),
            "opponent count cannot be fabricated")
        ok(!verifier.verify(good.copy(content=state.copy(
            equity=eq.copy(potShare=.99)))),
            "equity cannot be fabricated")
        ok(!verifier.verify(good.copy(content=state.copy(ownTurn=false))),
            "turn ownership is part of signature")
        ok(!verifier.verify(good.copy(signature=ByteArray(64))),
            "forged signature is denied")
        val impostor=KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        ok(!Ed25519PokerPacketVerifier(impostor.public.encoded).verify(good),
            "untrusted public key cannot authenticate provider")
        ok(verifier.verify(sign(state.copy(attestation=p.copy(
            frameSequence=14,proofId="proof-b")))),
            "new legitimate signed frame is acceptable")
        val command=PostActionReceipt("authorized-fixture","proof-effect","cmd-1",
            "hand-1",LiveAction.CHECK,7,5,16,1600,"state-after",
            true,false,10,10,120,120)
        fun signEffect(e:PostActionReceipt):SignedProviderEffect {
            val sign=Signature.getInstance("Ed25519")
            sign.initSign(pair.private)
            sign.update(SignedPokerFrameEncoding.encodeEffect(e))
            return SignedProviderEffect(e,sign.sign())
        }
        val signedEffect=signEffect(command)
        ok(verifier.verifyEffect(signedEffect),"legitimate causal effect signature")
        ok(!verifier.verifyEffect(signedEffect.copy(
            content=command.copy(commandId="cmd-2"))),
            "signed receipt cannot confirm a different command")
        ok(!verifier.verifyEffect(signedEffect.copy(
            content=command.copy(potAfter=121))),
            "a pot change cannot be substituted")
        ok(!verifier.verifyEffect(signedEffect.copy(
            content=command.copy(heroTurnClosed=false))),
            "turn closure cannot be forged")
        ok(!verifier.verifyEffect(SignedProviderEffect(command,good.signature)),
            "a state signature cannot be reused as an effect signature")
        println("SignedPokerProviderScenarios: $n/$n passed")
    }
}
