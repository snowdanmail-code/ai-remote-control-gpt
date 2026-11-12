package com.example.airemote

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets

/** T043-FILESYNC/1: deterministic bounded binary wire format; no JSON ambiguity.
 * All immutable Stage-2 SQL envelope values are copied, never rewritten.
 * Transport device identity is intentionally separate from game source namespace. */
object T043SyncProtocol {
    const val PROFILE = "HPKE-BASE-X25519-HKDF-SHA256-AES256GCM-ED25519-v1"
    const val VERSION = 1
    const val MAX_PAYLOAD = 1_048_576
    const val MAX_PACKET = MAX_PAYLOAD + 4096
    const val MAX_STRING = 256
    private const val MAGIC = 0x54303433 // ASCII T043
    private val hex64 = Regex("[0-9a-f]{64}")
    private val name = Regex("[A-Za-z0-9_-]{1,128}")

    data class Header(
        val senderDeviceId: String,
        val senderSigningKeyId: String,
        val senderKeyVersion: Int,
        val recipientDeviceId: String,
        val recipientEncryptionKeyId: String,
        val recipientKeyVersion: Int,
        val logicalSenderId: String,
        val logicalRecipientId: String,
        val streamId: String,
        val messageId: String,
        val sequenceNo: Long,
        val contentType: String,
        val originalEnvelopeSha256: String,
        val originalRawSha256: String,
        /** Exact Stage-2 identity fields; transport device IDs never replace them. */
        val sourceNamespace: String? = null,
        val relatedRecordType: String? = null,
        val relatedRecordKey: String? = null,
        val rulesetId: String? = null,
        val payloadEncoding: String = "JSON_UTF8",
        val schemaMajor: Int? = null,
        val schemaMinor: Int? = null,
        val canonicalizationId: String = "json-semantic.v1"
    ) {
        init {
            require(listOf(senderDeviceId,senderSigningKeyId,recipientDeviceId,
                recipientEncryptionKeyId).all { name.matches(it) }) { "Invalid device/key identity" }
            require(listOf(logicalSenderId,logicalRecipientId,streamId,messageId,contentType)
                .all { it.isNotBlank() && it.toByteArray(StandardCharsets.UTF_8).size <= MAX_STRING })
            require(senderKeyVersion > 0 && recipientKeyVersion > 0 && sequenceNo > 0)
            require(hex64.matches(originalEnvelopeSha256) && hex64.matches(originalRawSha256))
            require((relatedRecordType==null)==(relatedRecordKey==null))
            require((schemaMajor==null)==(schemaMinor==null))
            require(schemaMajor==null || schemaMajor > 0)
            require(schemaMinor==null || schemaMinor >= 0)
            require(payloadEncoding in setOf("JSON_UTF8","OPAQUE_BYTES"))
            require(canonicalizationId.isNotBlank())
        }
        fun originalEnvelope(): T043MessageFingerprint.Envelope =
            T043MessageFingerprint.Envelope(
                logicalSenderId,messageId,logicalRecipientId,streamId,sequenceNo,
                contentType,sourceNamespace,relatedRecordType,relatedRecordKey,
                rulesetId,payloadEncoding,schemaMajor,schemaMinor,canonicalizationId
            )
    }

    data class Packet(
        val header: Header,
        val enc: ByteArray,
        val ciphertext: ByteArray,
        val signature: ByteArray
    )

    fun headerBytes(h: Header): ByteArray = ByteArrayOutputStream().also { out ->
        DataOutputStream(out).use { d ->
            d.writeInt(MAGIC); d.writeInt(VERSION); put(d,PROFILE)
            put(d,h.senderDeviceId);put(d,h.senderSigningKeyId);d.writeInt(h.senderKeyVersion)
            put(d,h.recipientDeviceId);put(d,h.recipientEncryptionKeyId)
            d.writeInt(h.recipientKeyVersion)
            put(d,h.logicalSenderId);put(d,h.logicalRecipientId)
            put(d,h.streamId);put(d,h.messageId);d.writeLong(h.sequenceNo)
            put(d,h.contentType);put(d,h.originalEnvelopeSha256);put(d,h.originalRawSha256)
            putNullable(d,h.sourceNamespace);putNullable(d,h.relatedRecordType)
            putNullable(d,h.relatedRecordKey);putNullable(d,h.rulesetId)
            put(d,h.payloadEncoding)
            d.writeInt(h.schemaMajor ?: -1);d.writeInt(h.schemaMinor ?: -1)
            put(d,h.canonicalizationId)
        }
    }.toByteArray()

    private fun put(out: DataOutputStream, value: String) {
        val b=value.toByteArray(StandardCharsets.UTF_8)
        require(b.size <= MAX_STRING && b.isNotEmpty())
        out.writeShort(b.size);out.write(b)
    }
    private fun putNullable(out: DataOutputStream, value: String?) {
        if(value==null) out.writeShort(0xFFFF) else put(out,value)
    }
    private fun readNullable(input: DataInputStream): String? {
        input.mark(MAX_STRING+4)
        val n=input.readUnsignedShort()
        if(n==0xFFFF)return null
        input.reset()
        return read(input)
    }
    private fun read(input: DataInputStream): String {
        val n=input.readUnsignedShort()
        require(n in 1..MAX_STRING)
        val b=ByteArray(n);input.readFully(b)
        val decoded = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(b)).toString()
        require(decoded.toByteArray(StandardCharsets.UTF_8).contentEquals(b))
        return decoded
    }
    fun decodeHeader(bytes: ByteArray): Header {
        require(bytes.size <= 2048)
        return DataInputStream(ByteArrayInputStream(bytes)).use { i ->
            require(i.readInt()==MAGIC && i.readInt()==VERSION)
            require(read(i)==PROFILE)
            val sd=read(i);val sk=read(i);val sv=i.readInt()
            val rd=read(i);val rk=read(i);val rv=i.readInt()
            val ls=read(i);val lr=read(i);val st=read(i);val mid=read(i)
            val seq=i.readLong();val type=read(i);val eh=read(i);val rh=read(i)
            val src=readNullable(i);val rt=readNullable(i)
            val rr=readNullable(i);val rules=readNullable(i)
            val encoding=read(i)
            val major=i.readInt().let { if(it<0)null else it }
            val minor=i.readInt().let { if(it<0)null else it }
            val canon=read(i)
            require(i.available()==0) { "Unexpected fields" }
            Header(sd,sk,sv,rd,rk,rv,ls,lr,st,mid,seq,type,eh,rh,
                src,rt,rr,rules,encoding,major,minor,canon)
        }
    }
    private fun blob(out: DataOutputStream,b: ByteArray,max: Int) {
        require(b.size in 1..max)
        out.writeInt(b.size);out.write(b)
    }
    private fun blob(input: DataInputStream,max: Int): ByteArray {
        val size=input.readInt()
        require(size in 1..max && size <= input.available())
        return ByteArray(size).also { input.readFully(it) }
    }

    /** Sign domain-separated header + HPKE enc + exact encrypted bytes. */
    fun signingInput(header: ByteArray, enc: ByteArray, ciphertext: ByteArray): ByteArray =
        ByteArrayOutputStream().also { out ->
            val d=DataOutputStream(out)
            d.write("T043-SIGNED-PACKET-v1".toByteArray(StandardCharsets.US_ASCII))
            d.writeByte(0)
            blob(d,header,2048);blob(d,enc,64)
            blob(d,ciphertext,MAX_PAYLOAD+16)
        }.toByteArray()

    fun encode(packet: Packet): ByteArray {
        require(packet.enc.size==32 && packet.signature.size==64)
        return ByteArrayOutputStream().also { out ->
            val d=DataOutputStream(out)
            blob(d,headerBytes(packet.header),2048)
            blob(d,packet.enc,32)
            blob(d,packet.ciphertext,MAX_PAYLOAD+16)
            blob(d,packet.signature,64)
            require(out.size() <= MAX_PACKET)
        }.toByteArray()
    }
    fun decode(packet: ByteArray): Packet {
        require(packet.size <= MAX_PACKET)
        return DataInputStream(ByteArrayInputStream(packet)).use { d ->
            val header=decodeHeader(blob(d,2048))
            val enc=blob(d,32)
            val ct=blob(d,MAX_PAYLOAD+16)
            val sig=blob(d,64)
            require(enc.size==32 && sig.size==64 && d.available()==0)
            Packet(header,enc,ct,sig)
        }
    }
    /** ASCII path segments: cannot contain path traversal, separators or whitespace. */
    fun fileName(header: Header): String {
        val identity=(header.logicalSenderId+"|"+header.messageId).toByteArray(StandardCharsets.UTF_8)
        return "m-" + T043MessageFingerprint.sha256(identity) + "-" +
            header.sequenceNo.toString().padStart(20,'0') + ".t043"
    }
    fun mailboxPath(header: Header): String =
        "/t043/v1/inbox/" + header.recipientDeviceId + "/" +
            T043MessageFingerprint.sha256(header.streamId.toByteArray(StandardCharsets.UTF_8)).take(32)
}
