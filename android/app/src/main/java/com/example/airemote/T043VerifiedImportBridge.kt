package com.example.airemote

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.nio.charset.StandardCharsets

/** Only authenticated Stage3 ingress + an independently verified FULL hand can
 * reach the verified SQLite tables. No production verifier is installed.
 *
 * Unlike the LOCAL Stage2 import, an INBOUND hand NEVER enqueues another hand.
 * Instead, hand + events + inbox APPLIED + signed receipt outbox are committed
 * in ONE SQLite transaction. Any exception aborts all these effects.
 */
class T043VerifiedImportBridge(
    private val helper:AndroidHandLearningStore,
    private val ingress:T043SyncInboxTransport,
    private val registry:T043DeviceIdentityRegistry,
    private val localDeviceId:String
) {
    /** These dependencies must be provisioned by a trusted host. Null = fail closed. */
    @Synchronized fun importVerifiedAndCreateReceipt(
        originalSender:String,originalMessage:String,
        modelVerifier:PokerHandProofVerifier?,
        byteVerifier:T043ImmutableProofVerifier?,
        receiptSigner:T043SigningKey?
    ):T043WriteResult {
        if(modelVerifier==null || byteVerifier==null || receiptSigner==null)
            return T043WriteResult.REJECTED
        val authenticated=try { ingress.reloadAuthenticated(originalSender,originalMessage) }
            catch (_: Exception) { return T043WriteResult.REJECTED }
            ?: return T043WriteResult.REJECTED
        val (header,snapshot)=authenticated
        if(header.contentType!="poker.verified-hand.v1" ||
            header.logicalSenderId!=originalSender || header.messageId!=originalMessage ||
            header.recipientDeviceId!=localDeviceId)
            return T043WriteResult.REJECTED
        val digest=T043MessageFingerprint.digest(snapshot,
            header.payloadEncoding,header.canonicalizationId)
        if(digest.quarantine || header.schemaMajor!=1 || header.schemaMinor!=0 ||
            digest.rawSha256!=header.originalRawSha256)
            return T043WriteResult.REJECTED
        val record=try { CanonicalPokerJson.decodeVerifiedHand(
            snapshot.toString(StandardCharsets.UTF_8)) }
            catch (_: Exception) { return T043WriteResult.REJECTED }
        if(record.handRecordId!=header.relatedRecordKey ||
            header.relatedRecordType!="hand" ||
            header.logicalSenderId!=record.terminalObservation.source.namespace ||
            record.rulesetId!=header.rulesetId ||
            record.verificationPolicyId!=PokerVerificationPolicies.COMPLETE_RECORD_V1)
            return T043WriteResult.REJECTED
        if(PokerHandVerificationGate.verify(record,modelVerifier) !=
            PokerHandVerificationStatus.VERIFIED) return T043WriteResult.REJECTED
        val roundTrip=T043MessageFingerprint.digest(
            CanonicalPokerJson.encodeVerifiedHand(record).toByteArray(StandardCharsets.UTF_8),
            "JSON_UTF8",T043MessageFingerprint.JSON_CANONICALIZATION)
        if(roundTrip.semanticSha256!=digest.semanticSha256)return T043WriteResult.REJECTED
        val bytesApproved=try {
            byteVerifier.verifySnapshot(snapshot.copyOf(),digest.rawSha256,record)
        } catch (_: Exception) { false }
        if(!bytesApproved)return T043WriteResult.REJECTED
        val frozen=try { CanonicalPokerJson.decodeVerifiedHand(
            snapshot.toString(StandardCharsets.UTF_8)) }
            catch (_:Exception) { return T043WriteResult.REJECTED }
        if(frozen!=record || T043MessageFingerprint.sha256(snapshot)!=digest.rawSha256)
            return T043WriteResult.REJECTED

        return try {
            val db=helper.writableDatabase
            db.beginTransaction()
            try {
                val received=db.rawQuery(
                    "SELECT payload_bytes,raw_sha256,envelope_sha256,processing_state,sequence_no " +
                    "FROM t043_sync_inbox WHERE sender_id=? AND message_id=?",
                    arrayOf(originalSender,originalMessage)).use { row ->
                    if(!row.moveToFirst()) null else listOf(
                        row.getBlob(0),row.getString(1),row.getString(2),
                        row.getString(3),row.getLong(4))
                } ?: return T043WriteResult.REJECTED
                val receivedBytes=received[0] as ByteArray
                require(receivedBytes.contentEquals(snapshot) &&
                    received[1]==digest.rawSha256 &&
                    received[2]==header.originalEnvelopeSha256 &&
                    received[4]==header.sequenceNo) { "Inbox proof/source mismatch" }

                if(received[3]=="APPLIED") {
                    val receiptId=stableReceiptId(header)
                    val hasReceipt=db.rawQuery(
                        "SELECT 1 FROM t043_sync_outbox WHERE sender_id=? AND message_id=?",
                        arrayOf(localDeviceId,receiptId)).use { it.moveToFirst() }
                    require(hasReceipt) { "Applied hand without durable receipt" }
                    db.setTransactionSuccessful()
                    return T043WriteResult.ALREADY_SEEN
                }
                if(received[3]!="RECEIVED")return T043WriteResult.REJECTED
                val contiguous=db.rawQuery(
                    "SELECT last_contiguous_no FROM t043_sync_cursors WHERE sender_id=? " +
                    "AND recipient_id=? AND stream_id=? AND direction='INBOUND'",
                    arrayOf(originalSender,header.logicalRecipientId,header.streamId)).use { row ->
                    require(row.moveToFirst());row.getLong(0)
                }
                if(contiguous==Long.MAX_VALUE ||
                    contiguous+1!=header.sequenceNo) return T043WriteResult.REJECTED

                db.execSQL("SAVEPOINT t043_trusted_inbound")
                val original=db.rawQuery(
                    "SELECT payload_bytes,payload_sha256 FROM t043_verified_hands " +
                    "WHERE hand_record_id=?",arrayOf(frozen.handRecordId)).use { row ->
                    if(!row.moveToFirst())null
                    else Pair(row.getBlob(0),row.getString(1))
                }
                val result=if(original==null) {
                    addConfirmedHand(db,frozen,snapshot,digest)
                    T043SyncReceipt.VERIFIED_IMPORTED
                } else {
                    val old=T043MessageFingerprint.digest(original.first,
                        "JSON_UTF8",T043MessageFingerprint.JSON_CANONICALIZATION)
                    check(!old.quarantine && old.semanticSha256==original.second) {
                        "Stored verified hand checksum damaged"
                    }
                    if(original.second != digest.semanticSha256) {
                        db.execSQL("ROLLBACK TO SAVEPOINT t043_trusted_inbound")
                        db.execSQL("RELEASE SAVEPOINT t043_trusted_inbound")
                        val v=ContentValues().apply {
                            put("entity_type","hand");put("identity_key",frozen.handRecordId)
                            put("stored_envelope_sha256",original.second)
                            put("incoming_envelope_sha256",digest.comparisonDigest)
                            put("reason","TRUSTED_INBOUND_CONFLICT")
                            put("detected_at_ms",System.currentTimeMillis())
                        }
                        db.insertWithOnConflict("t043_conflicts",null,v,
                            SQLiteDatabase.CONFLICT_IGNORE)
                        db.setTransactionSuccessful()
                        return T043WriteResult.CONFLICT
                    }
                    T043SyncReceipt.DUPLICATE_COMMITTED
                }
                db.execSQL("RELEASE SAVEPOINT t043_trusted_inbound")
                val ms=System.currentTimeMillis()
                // Receiver's signing key must belong to the local pinned device.
                val own=registry.requireReceiptSignerForRoute(localDeviceId)
                val body=T043SyncReceipt.Body(
                    signerDeviceId=localDeviceId,
                    signingKeyId=own.signingKeyId,
                    signingKeyVersion=own.signingVersion,
                    intendedDeviceId=header.senderDeviceId,
                    originalLogicalSenderId=originalSender,
                    originalMessageId=originalMessage,
                    originalStreamId=header.streamId,
                    originalSequenceNo=header.sequenceNo,
                    originalEnvelopeSha256=header.originalEnvelopeSha256,
                    result=result,
                    committedRecordId=frozen.handRecordId
                )
                val receipt=T043SyncReceipt.sign(body,receiptSigner)
                require(T043SyncCrypto.verifyEd25519(
                    own.signingPublicKey,T043SyncReceipt.bodyBytes(body),
                    receipt.copyOfRange(receipt.size-64,receipt.size))) {
                    "Receipt signer does not match the pinned receiving device"
                }
                enqueueReceiptInTransaction(db,header,body,receipt,ms)
                db.execSQL("UPDATE t043_sync_inbox SET processing_state='APPLIED'," +
                    "applied_at_ms=? WHERE sender_id=? AND message_id=? " +
                    "AND processing_state='RECEIVED'",
                    arrayOf(ms,originalSender,originalMessage))
                db.execSQL("UPDATE t043_sync_cursors SET last_contiguous_no=?,updated_at_ms=? " +
                    "WHERE sender_id=? AND recipient_id=? AND stream_id=? AND direction='INBOUND'",
                    arrayOf(header.sequenceNo,ms,originalSender,header.logicalRecipientId,header.streamId))
                db.setTransactionSuccessful()
                T043WriteResult.APPLIED
            } finally { db.endTransaction() }
        } catch (_: Exception) { T043WriteResult.STORAGE_ERROR }
    }

    private fun addConfirmedHand(db:SQLiteDatabase,record:VerifiedPokerHandRecord,
                                 original:ByteArray,
                                 d:T043MessageFingerprint.Digest) {
        val ms=System.currentTimeMillis()
        db.execSQL("INSERT OR IGNORE INTO t043_sources(namespace,first_seen_ms) VALUES(?,?)",
            arrayOf(record.terminalObservation.source.namespace,ms))
        val h=ContentValues().apply {
            put("hand_record_id",record.handRecordId)
            put("namespace",record.terminalObservation.source.namespace)
            put("ruleset_id",record.rulesetId)
            put("verification_policy_id",record.verificationPolicyId)
            put("schema_major",record.schemaMajor);put("schema_minor",record.schemaMinor)
            put("payload_json",original.toString(StandardCharsets.UTF_8))
            put("payload_bytes",original)
            put("payload_sha256",requireNotNull(d.semanticSha256))
            put("verified_at_ms",ms)
        }
        db.insertOrThrow("t043_verified_hands",null,h)
        record.terminalObservation.actions.filter { it.evidence==PokerEvidence.INDEPENDENT }
            .forEach { a ->
                val eventKey=requireNotNull(record.terminalObservation.stableEventKey(a.eventId))
                val eventJson=org.json.JSONObject()
                    .put("eventId",a.eventId).put("sequence",a.sequence)
                    .put("street",a.street.name).put("kind",a.kind.name)
                    .put("actorSeat",a.actorSeat ?: org.json.JSONObject.NULL)
                    .put("amountChips",a.amountChips ?: org.json.JSONObject.NULL)
                    .put("evidence",a.evidence.name).toString()
                val ev=ContentValues().apply {
                    put("event_key",eventKey);put("hand_record_id",record.handRecordId)
                    put("ruleset_id",record.rulesetId);put("event_id",a.eventId)
                    put("seq",a.sequence);put("evidence_level","INDEPENDENT")
                    put("payload_json",eventJson)
                    put("payload_sha256",T043MessageFingerprint.digest(
                        eventJson.toByteArray(StandardCharsets.UTF_8),"JSON_UTF8",
                        T043MessageFingerprint.JSON_CANONICALIZATION).comparisonDigest)
                }
                db.insertOrThrow("t043_verified_events",null,ev)
            }
    }

    private fun stableReceiptId(h:T043SyncProtocol.Header):String {
        return "receipt-" +
            T043SyncProtocol.stableIdentityHash(h.logicalSenderId,h.messageId)
    }

    private fun enqueueReceiptInTransaction(db:SQLiteDatabase,h:T043SyncProtocol.Header,
                                            b:T043SyncReceipt.Body,
                                            original:ByteArray,ms:Long) {
        val id=stableReceiptId(h)
        val prior=db.rawQuery(
            "SELECT payload_bytes FROM t043_sync_outbox WHERE sender_id=? AND message_id=?",
            arrayOf(localDeviceId,id)).use { c ->
            if(c.moveToFirst())c.getBlob(0) else null
        }
        if(prior!=null) {
            check(prior.contentEquals(original)) { "Receipt conflict on replay" }
            return
        }
        val stream="receipts-v1"
        db.execSQL("INSERT OR IGNORE INTO t043_sync_cursors(" +
            "sender_id,recipient_id,stream_id,direction,updated_at_ms) VALUES(?,?,?,'OUTBOUND',?)",
            arrayOf(localDeviceId,h.senderDeviceId,stream,ms))
        val previous=db.rawQuery(
            "SELECT last_allocated_no FROM t043_sync_cursors " +
            "WHERE sender_id=? AND recipient_id=? AND stream_id=? AND direction='OUTBOUND'",
            arrayOf(localDeviceId,h.senderDeviceId,stream)).use { c ->
            check(c.moveToFirst());c.getLong(0)
        }
        check(previous<Long.MAX_VALUE)
        val next=previous+1
        db.execSQL("UPDATE t043_sync_cursors SET last_allocated_no=?,max_seen_no=?," +
            "updated_at_ms=? WHERE sender_id=? AND recipient_id=? AND stream_id=? " +
            "AND direction='OUTBOUND'",
            arrayOf(next,next,ms,localDeviceId,h.senderDeviceId,stream))
        val e=T043MessageFingerprint.Envelope(
            senderId=localDeviceId,messageId=id,recipientId=h.senderDeviceId,
            streamId=stream,sequenceNo=next,contentType="t043.receipt.v1",
            relatedRecordType="receipt",relatedRecordKey=h.messageId,
            payloadEncoding="OPAQUE_BYTES",
            canonicalizationId=T043MessageFingerprint.RAW_CANONICALIZATION
        )
        val d=T043MessageFingerprint.digest(original,e.payloadEncoding,e.canonicalizationId)
        val values=ContentValues().apply {
            put("sender_id",e.senderId);put("message_id",e.messageId)
            put("recipient_id",e.recipientId);put("stream_id",e.streamId)
            put("sequence_no",e.sequenceNo);put("content_type",e.contentType)
            put("related_record_type",e.relatedRecordType)
            put("related_record_key",e.relatedRecordKey)
            put("payload_encoding",e.payloadEncoding)
            put("canonicalization_id",e.canonicalizationId)
            put("comparison_mode",d.comparisonMode)
            put("payload_bytes",original);put("raw_sha256",d.rawSha256)
            put("envelope_sha256",T043MessageFingerprint.envelopeSha256(e,d))
            put("delivery_state","PENDING");put("created_at_ms",ms)
        }
        db.insertOrThrow("t043_sync_outbox",null,values)
    }
}
