package com.example.airemote

import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.hpke.HPKE
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** No production key generated here. Device enrollment and key access are external.
 * Never put secrets in logs, exceptions, manifests or GitHub fixtures.
 * Library: bcprov-jdk18on 1.85, RFC 9180 base HPKE and independent Ed25519 signature. */
fun interface T043SigningKey {
    /** Sign the EXACT bytes provided; must be backed by protected local key storage. */
    fun sign(input: ByteArray): ByteArray
}
interface T043HpkeSecret {
    /** Give a temporary private key to a synchronous decrypt callback; purge it afterwards. */
    fun <T> withPrivateX25519(block: (ByteArray)->T): T
}
data class T043PinnedDevice(
    val deviceId: String,
    val signingKeyId: String,
    val signingVersion: Int,
    val signingPublicKey: ByteArray,
    val encryptionKeyId: String,
    val encryptionVersion: Int,
    val encryptionPublicKey: ByteArray,
    /** Game namespaces explicitly delegated to this authenticated device. */
    val allowedLogicalSenders: Set<String> = emptySet(),
    val revoked: Boolean = false
) {
    init {
        require(signingPublicKey.size==32 && encryptionPublicKey.size==32)
    }
}

/** The registry is provisioned out-of-band; FTP listings can NEVER enroll trusted keys. */
class T043DeviceIdentityRegistry(devices: Collection<T043PinnedDevice>) {
    private val byId = devices.groupBy { it.deviceId }.also {
        require(it.values.all { group -> group.size==1 }) { "Ambiguous device identity" }
    }.mapValues { (_, group) -> group.single() }

    fun requireSender(header: T043SyncProtocol.Header): T043PinnedDevice =
        requireNotNull(byId[header.senderDeviceId]) { "Unknown sender device" }.also { pinned ->
            require(!pinned.revoked && pinned.signingKeyId==header.senderSigningKeyId &&
                pinned.signingVersion==header.senderKeyVersion &&
                header.logicalSenderId in pinned.allowedLogicalSenders) {
                "Untrusted signing key or unauthorized game namespace"
            }
        }

    /** Route selection only consults a locally provisioned, trusted key registry. */
    fun requireReceiptSignerForRoute(deviceId:String):T043PinnedDevice =
        requireNotNull(byId[deviceId]) { "Unknown signing device" }.also {
            require(!it.revoked) { "Revoked signing device" }
        }
    fun requireEncryptionRecipientForRoute(deviceId:String):T043PinnedDevice =
        requireNotNull(byId[deviceId]) { "Unknown recipient device" }.also {
            require(!it.revoked) { "Revoked recipient device" }
        }

    /** App receipts identify the device itself, not an observation namespace. */
    fun requireReceiptSigner(deviceId:String,keyId:String,version:Int):T043PinnedDevice =
        requireNotNull(byId[deviceId]) { "Unknown receipt signer" }.also { pinned ->
            require(!pinned.revoked && pinned.signingKeyId==keyId &&
                pinned.signingVersion==version) { "Invalid receipt signing key" }
        }

    fun requireRecipient(header: T043SyncProtocol.Header): T043PinnedDevice =
        requireNotNull(byId[header.recipientDeviceId]) { "Unknown recipient device" }.also { pinned ->
            require(!pinned.revoked && pinned.encryptionKeyId==header.recipientEncryptionKeyId &&
                pinned.encryptionVersion==header.recipientKeyVersion) { "Untrusted encryption key" }
        }
}

/** Transport encryption is independent of Stage-1 game-result proof verification. */
object T043SyncCrypto {
    const val SUITE = T043SyncProtocol.PROFILE
    private val hpkeInfo = "T043-FILESYNC-1/HPKE".toByteArray(StandardCharsets.US_ASCII)
    private fun hpke() = HPKE(HPKE.mode_base,HPKE.kem_X25519_SHA256,
        HPKE.kdf_HKDF_SHA256,HPKE.aead_AES_GCM256)

    /** Use only once per logical message; persistent prepared packet reused on retries. */
    fun seal(
        header: T043SyncProtocol.Header,
        originalBytes: ByteArray,
        recipientPublicX25519: ByteArray,
        signingKey: T043SigningKey
    ): ByteArray {
        require(originalBytes.isNotEmpty() && originalBytes.size <= T043SyncProtocol.MAX_PAYLOAD)
        require(recipientPublicX25519.size==32)
        require(MessageDigest.isEqual(
            hex(T043MessageFingerprint.sha256(originalBytes)),
            hex(header.originalRawSha256))) { "Original payload mismatch" }
        val computed=T043MessageFingerprint.digest(originalBytes,
            header.payloadEncoding,header.canonicalizationId)
        require(T043MessageFingerprint.envelopeSha256(
            header.originalEnvelope(),computed)==header.originalEnvelopeSha256) {
            "Outbox envelope is inconsistent"
        }
        val immutableHeader = T043SyncProtocol.headerBytes(header)
        val receiver = X25519PublicKeyParameters(recipientPublicX25519,0)
        val context = hpke().setupBaseS(receiver,hpkeInfo)
        val enc = context.encapsulation
        val ciphertext = context.seal(immutableHeader,originalBytes.copyOf())
        val toSign=T043SyncProtocol.signingInput(immutableHeader,enc,ciphertext)
        val signature=signingKey.sign(toSign)
        require(signature.size==64) { "Invalid signer output" }
        return T043SyncProtocol.encode(T043SyncProtocol.Packet(header,enc,ciphertext,signature))
    }

    fun open(
        signedPacket: ByteArray,
        registry: T043DeviceIdentityRegistry,
        recipientDeviceId: String,
        recipientKey: T043HpkeSecret
    ): Pair<T043SyncProtocol.Header,ByteArray> {
        val packet=T043SyncProtocol.decode(signedPacket)
        val h=packet.header
        require(h.recipientDeviceId==recipientDeviceId) { "Wrong recipient" }
        val sender=registry.requireSender(h)
        val receiver=registry.requireRecipient(h)
        val signed=T043SyncProtocol.signingInput(
            T043SyncProtocol.headerBytes(h),packet.enc,packet.ciphertext)
        require(verifyEd25519(sender.signingPublicKey,signed,packet.signature)) {
            "Sender signature not authorized"
        }
        val clear=recipientKey.withPrivateX25519 { raw ->
            require(raw.size==32)
            val copy=raw.copyOf()
            try {
                val privateKey=X25519PrivateKeyParameters(copy,0)
                val pair=AsymmetricCipherKeyPair(privateKey.generatePublicKey(),privateKey)
                require(MessageDigest.isEqual(receiver.encryptionPublicKey,
                    privateKey.generatePublicKey().encoded)) { "Device key version mismatch" }
                hpke().setupBaseR(packet.enc,pair,hpkeInfo)
                    .open(T043SyncProtocol.headerBytes(h),packet.ciphertext)
            } finally { copy.fill(0) }
        }
        require(clear.size <= T043SyncProtocol.MAX_PAYLOAD)
        val computed=T043MessageFingerprint.digest(clear,h.payloadEncoding,h.canonicalizationId)
        require(T043MessageFingerprint.envelopeSha256(h.originalEnvelope(),computed) ==
            h.originalEnvelopeSha256) { "Original Stage-2 envelope does not match plaintext" }
        require(MessageDigest.isEqual(
            hex(T043MessageFingerprint.sha256(clear)),
            hex(h.originalRawSha256))) { "Payload digest mismatch" }
        return h to clear
    }

    fun signEd25519(privateSeed: ByteArray,input: ByteArray): ByteArray {
        require(privateSeed.size==32)
        val signer=Ed25519Signer()
        signer.init(true,Ed25519PrivateKeyParameters(privateSeed,0))
        signer.update(input,0,input.size)
        return signer.generateSignature()
    }
    fun verifyEd25519(publicKey: ByteArray,input: ByteArray,signature: ByteArray): Boolean {
        if(publicKey.size!=32 || signature.size!=64)return false
        return try {
            val verifier=Ed25519Signer()
            verifier.init(false,Ed25519PublicKeyParameters(publicKey,0))
            verifier.update(input,0,input.size)
            verifier.verifySignature(signature)
        } catch (_: Exception) { false }
    }
    private fun hex(hash: String): ByteArray {
        require(hash.length==64)
        return ByteArray(32) { i -> hash.substring(i*2,i*2+2).toInt(16).toByte() }
    }
}
