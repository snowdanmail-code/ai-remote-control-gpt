package com.example.airemote

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Canonical encoding for a PERMITTED upstream poker-state provider.
 *
 * A signature MUST cover the WHOLE game state: cards, board, pot/call, turn,
 * legal actions, chosen size and equity. Validating only a signed proof ID
 * would let untrusted code substitute amounts/actions under the same ID.
 *
 * The user's ChatGPT session does not sign or authenticate game data.
 */
data class SignedPokerProviderFrame(
    val content: IndependentlyAuthenticatedPokerFrame,
    val signature: ByteArray
)

object SignedPokerFrameEncoding {
    fun encode(packet: IndependentlyAuthenticatedPokerFrame): ByteArray {
        val out=ByteArrayOutputStream(2048)
        DataOutputStream(out).use { d ->
            fun str(s:String){ require(s.length <= 2048); d.writeUTF(s) }
            fun maybe(v:Long?){ d.writeBoolean(v!=null); if(v!=null)d.writeLong(v) }
            fun list(values:List<String>){
                require(values.size<=52)
                d.writeInt(values.size)
                values.forEach(::str)
            }
            val p=packet.attestation
            str("T042_SIGNED_PLAYER_FRAME_V1")
            str(p.providerId);str(p.proofId);str(p.tableId);str(p.handId)
            str(p.turnId);str(p.packageName)
            d.writeLong(p.epoch);d.writeLong(p.windowGeneration)
            d.writeLong(p.frameSequence);d.writeLong(p.capturedAtMs)
            d.writeLong(p.expiresAtMs)
            str(packet.stage.name)
            d.writeBoolean(packet.ownTurn)
            list(packet.legalActions.map { it.name }.sorted())
            maybe(packet.potHundredths); maybe(packet.callHundredths)
            maybe(packet.timerRemainingMs)
            list(packet.ownHoleCards)
            list(packet.publicCards)
            str(packet.stateFingerprint)
            maybe(packet.selectedBetHundredths)
            maybe(packet.minBetHundredths)
            maybe(packet.maxBetHundredths)
            d.writeBoolean(packet.equity != null)
            packet.equity?.let { e ->
                str(e.handId);str(e.turnId)
                d.writeLong(e.captureEpoch);d.writeLong(e.frameSequence)
                d.writeLong(java.lang.Double.doubleToLongBits(e.potShare))
                d.writeInt(e.independentSampleCount)
                d.writeBoolean(e.externallyVerified)
                d.writeBoolean(e.exactEnumeration)
                str(e.provenance)
            }
            d.writeBoolean(packet.verifiedActiveOpponentCount!=null)
            packet.verifiedActiveOpponentCount?.let(d::writeInt)
        }
        return out.toByteArray()
    }
}

/**
 * Accept ONLY a provisioned public key belonging to an independently
 * authorized game-state integration. Never generate/sign a private key on
 * the handset to "authenticate" its own OCR.
 */
class Ed25519PokerPacketVerifier(publicKeyDer: ByteArray) {
    private val key=KeyFactory.getInstance("Ed25519").generatePublic(
        X509EncodedKeySpec(publicKeyDer.clone()))
    fun verify(signed: SignedPokerProviderFrame): Boolean = try {
        if (signed.signature.size != 64) return false
        val v=Signature.getInstance("Ed25519")
        v.initVerify(key)
        v.update(SignedPokerFrameEncoding.encode(signed.content))
        v.verify(signed.signature)
    } catch (_: Exception) { false }
}
