package com.example.airemote

import android.database.sqlite.SQLiteDatabase
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets

/** Permanent, device-signed application receipt; *not* an FTP server reply. */
object T043SyncReceipt {
    const val VERIFIED_IMPORTED="VERIFIED_IMPORTED"
    const val DUPLICATE_COMMITTED="DUPLICATE_COMMITTED"
    const val RECEIVED_DURABLE="RECEIVED_DURABLE"
    const val DIAGNOSTIC_APPLIED="DIAGNOSTIC_APPLIED"
    private const val MAGIC=0x54335243 // T3RC
    private val sha=Regex("[a-f0-9]{64}")
    data class Body(
        val signerDeviceId:String,
        val signingKeyId:String,
        val signingKeyVersion:Int,
        val intendedDeviceId:String,
        val originalLogicalSenderId:String,
        val originalMessageId:String,
        val originalStreamId:String,
        val originalSequenceNo:Long,
        val originalEnvelopeSha256:String,
        val result:String,
        val committedRecordId:String
    ) {
        init {
            require(listOf(signerDeviceId,signingKeyId,intendedDeviceId,originalLogicalSenderId,
                originalMessageId,originalStreamId,result,committedRecordId)
                .all { it.isNotBlank() && it.toByteArray(StandardCharsets.UTF_8).size<=256 })
            require(signingKeyVersion>0 && originalSequenceNo>0)
            require(sha.matches(originalEnvelopeSha256))
            require(result in setOf(VERIFIED_IMPORTED,DUPLICATE_COMMITTED,
                RECEIVED_DURABLE,DIAGNOSTIC_APPLIED))
        }
    }
    data class Signed(val body:Body,val signature:ByteArray)

    private fun write(out:DataOutputStream,s:String) {
        val bytes=s.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size in 1..256)
        out.writeShort(bytes.size);out.write(bytes)
    }
    private fun read(input:DataInputStream):String {
        val length=input.readUnsignedShort()
        require(length in 1..256)
        return ByteArray(length).also { input.readFully(it) }.toString(StandardCharsets.UTF_8)
    }
    fun bodyBytes(b:Body):ByteArray=ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC);out.writeInt(1)
            write(out,b.signerDeviceId);write(out,b.signingKeyId);out.writeInt(b.signingKeyVersion)
            write(out,b.intendedDeviceId);write(out,b.originalLogicalSenderId)
            write(out,b.originalMessageId);write(out,b.originalStreamId)
            out.writeLong(b.originalSequenceNo);write(out,b.originalEnvelopeSha256)
            write(out,b.result);write(out,b.committedRecordId)
        }
    }.toByteArray()
    fun sign(body:Body,key:T043SigningKey):ByteArray {
        val data=bodyBytes(body)
        val sig=key.sign(data)
        require(sig.size==64)
        return data + sig
    }
    fun decode(raw:ByteArray):Signed {
        require(raw.size in 64..2048)
        val bodyLength=raw.size-64
        val data=raw.copyOfRange(0,bodyLength)
        val body=DataInputStream(ByteArrayInputStream(data)).use { input ->
            require(input.readInt()==MAGIC && input.readInt()==1)
            val signer=read(input);val key=read(input);val version=input.readInt()
            val intended=read(input);val source=read(input);val id=read(input)
            val stream=read(input);val seq=input.readLong()
            val env=read(input);val result=read(input);val record=read(input)
            require(input.available()==0)
            Body(signer,key,version,intended,source,id,stream,seq,env,result,record)
        }
        return Signed(body,raw.copyOfRange(bodyLength,raw.size))
    }
    fun verify(raw:ByteArray,registry:T043DeviceIdentityRegistry):Body {
        val signed=decode(raw)
        val b=signed.body
        val signer=registry.requireReceiptSigner(b.signerDeviceId,
            b.signingKeyId,b.signingKeyVersion)
        require(T043SyncCrypto.verifyEd25519(signer.signingPublicKey,
            bodyBytes(b),signed.signature)) { "Untrusted receipt signature" }
        return b
    }
}

/** Signed receiving device + original outbox + pinned route MUST agree.
 * A RECEIVED_DURABLE or DIAGNOSTIC_APPLIED receipt can never ACK a verified hand.
 */
class T043SyncReceiptManager(
    private val helper:AndroidHandLearningStore,
    private val gateway:T043SyncSqlGateway,
    private val routes:T043RouteStore,
    private val registry:T043DeviceIdentityRegistry
) {
    @Synchronized fun consumeVerifiedReceipt(raw:ByteArray,
                  localDeviceId:String):T043WriteResult {
        val body=try { T043SyncReceipt.verify(raw,registry) }
                 catch (_: Exception) { return T043WriteResult.REJECTED }
        if(body.intendedDeviceId!=localDeviceId ||
            body.result !in setOf(T043SyncReceipt.VERIFIED_IMPORTED,
                T043SyncReceipt.DUPLICATE_COMMITTED))
            return T043WriteResult.REJECTED
        return try {
            val s=gateway.readOutbox(body.originalLogicalSenderId,body.originalMessageId)
                ?: return T043WriteResult.REJECTED
            val e=s.envelope
            if(e.contentType!="poker.verified-hand.v1" ||
                e.streamId!=body.originalStreamId ||
                e.sequenceNo!=body.originalSequenceNo ||
                s.envelopeSha256!=body.originalEnvelopeSha256 ||
                e.relatedRecordKey!=body.committedRecordId)
                return T043WriteResult.REJECTED
            val pinned=routes.recover(e.senderId,e.messageId,s.envelopeSha256)
                ?: return T043WriteResult.REJECTED
            if(pinned.header.recipientDeviceId!=body.signerDeviceId ||
                pinned.header.senderDeviceId!=localDeviceId)
                return T043WriteResult.REJECTED
            val receiptHash=T043MessageFingerprint.sha256(raw)
            val db=helper.writableDatabase
            db.beginTransaction()
            try {
                val current=gateway.readOutbox(e.senderId,e.messageId)
                    ?: return T043WriteResult.REJECTED
                val result=if(current.deliveryState=="ACKNOWLEDGED") {
                    val previous=db.rawQuery("SELECT acknowledgement_ref FROM t043_sync_outbox " +
                        "WHERE sender_id=? AND message_id=?",arrayOf(e.senderId,e.messageId))
                        .use { row -> check(row.moveToFirst());row.getString(0) }
                    if(previous!=receiptHash) T043WriteResult.CONFLICT
                    else T043WriteResult.ALREADY_SEEN
                } else {
                    gateway.markAuthenticatedAckInTransaction(db,current,receiptHash,
                        System.currentTimeMillis())
                    T043WriteResult.APPLIED
                }
                db.setTransactionSuccessful()
                result
            } finally { db.endTransaction() }
        } catch (_: Exception) { T043WriteResult.STORAGE_ERROR }
    }
}
