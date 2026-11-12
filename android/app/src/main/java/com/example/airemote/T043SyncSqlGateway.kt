package com.example.airemote

import android.database.sqlite.SQLiteDatabase
import java.nio.charset.StandardCharsets

/** Read-only snapshot of the immutable Stage-2 message. Never expose a writable DB callback. */
data class T043OutboxSnapshot(
    val envelope:T043MessageFingerprint.Envelope,
    val bytes:ByteArray,
    val rawSha256:String,
    val envelopeSha256:String,
    val deliveryState:String
)
class T043SyncSqlGateway(private val helper:AndroidHandLearningStore) {
    @Synchronized fun readOutbox(sender:String,message:String):T043OutboxSnapshot? {
        val db=helper.readableDatabase
        return db.rawQuery("SELECT * FROM t043_sync_outbox WHERE sender_id=? AND message_id=?",
            arrayOf(sender,message)).use { row ->
            if(!row.moveToFirst())return@use null
            fun string(col:String):String? {
                val i=row.getColumnIndexOrThrow(col)
                return if(row.isNull(i))null else row.getString(i)
            }
            fun integer(col:String):Int? {
                val i=row.getColumnIndexOrThrow(col)
                return if(row.isNull(i))null else row.getInt(i)
            }
            fun required(col:String)=requireNotNull(string(col)) { "Corrupt outbox $col" }
            val e=T043MessageFingerprint.Envelope(
                required("sender_id"),required("message_id"),required("recipient_id"),
                required("stream_id"),row.getLong(row.getColumnIndexOrThrow("sequence_no")),
                required("content_type"),string("source_namespace"),
                string("related_record_type"),string("related_record_key"),string("ruleset_id"),
                required("payload_encoding"),integer("payload_schema_major"),
                integer("payload_schema_minor"),required("canonicalization_id")
            )
            val bytes=row.getBlob(row.getColumnIndexOrThrow("payload_bytes"))
            val d=T043MessageFingerprint.digest(bytes,e.payloadEncoding,e.canonicalizationId)
            require(d.rawSha256==required("raw_sha256") &&
                d.semanticSha256==string("semantic_sha256") &&
                d.comparisonMode==required("comparison_mode")) { "Corrupt stored payload" }
            val envelopeHash=T043MessageFingerprint.envelopeSha256(e,d)
            require(envelopeHash==required("envelope_sha256")) { "Corrupt stored envelope" }

            if(e.contentType=="poker.verified-hand.v1") {
                require(e.relatedRecordType=="hand" && !e.relatedRecordKey.isNullOrBlank())
                db.rawQuery("SELECT payload_bytes,payload_sha256 FROM t043_verified_hands " +
                    "WHERE hand_record_id=?",arrayOf(e.relatedRecordKey)).use { h ->
                    require(h.moveToFirst()) { "Unverified outgoing hand" }
                    val actual=h.getBlob(0)
                    require(actual.contentEquals(bytes) &&
                        h.getString(1)==d.semanticSha256) { "Verified hand drift" }
                }
            }
            T043OutboxSnapshot(e,bytes.copyOf(),d.rawSha256,envelopeHash,
                required("delivery_state"))
        }
    }

    @Synchronized fun markPublished(s:T043OutboxSnapshot) {
        val db=helper.writableDatabase
        db.beginTransaction()
        try {
            val fresh=readOutbox(s.envelope.senderId,s.envelope.messageId)
            require(fresh!=null && fresh.envelopeSha256==s.envelopeSha256 &&
                fresh.bytes.contentEquals(s.bytes)) { "Outbox changed before send" }
            require(fresh.deliveryState in setOf("PENDING","RETRY","LEASED","SENT_UNCONFIRMED"))
            db.execSQL("UPDATE t043_sync_outbox SET delivery_state='SENT_UNCONFIRMED'," +
                "sent_at_ms=?,attempts=attempts+1 WHERE sender_id=? AND message_id=?",
                arrayOf(System.currentTimeMillis(),s.envelope.senderId,s.envelope.messageId))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** This operation is intentionally NOT a general-purpose ACK setter.
     * Only a cryptographically validated and matched receipt manager may call it. */
    internal fun markAuthenticatedAckInTransaction(
        db:SQLiteDatabase,s:T043OutboxSnapshot,receiptHash:String,ms:Long
    ) {
        require(s.deliveryState in setOf("PENDING","LEASED","RETRY","SENT_UNCONFIRMED"))
        db.execSQL("UPDATE t043_sync_outbox SET delivery_state='ACKNOWLEDGED'," +
            "acknowledged_at_ms=?,acknowledgement_ref=? WHERE sender_id=? AND message_id=?",
            arrayOf(ms,receiptHash,s.envelope.senderId,s.envelope.messageId))
        val e=s.envelope
        val contiguous=db.rawQuery("SELECT last_contiguous_no FROM t043_sync_cursors " +
            "WHERE sender_id=? AND recipient_id=? AND stream_id=? AND direction='OUTBOUND'",
            arrayOf(e.senderId,e.recipientId,e.streamId)).use { row ->
            require(row.moveToFirst()); row.getLong(0)
        }
        var current=contiguous
        while(current<Long.MAX_VALUE) {
            val next=current+1
            val state=db.rawQuery("SELECT delivery_state FROM t043_sync_outbox " +
                "WHERE sender_id=? AND recipient_id=? AND stream_id=? AND sequence_no=?",
                arrayOf(e.senderId,e.recipientId,e.streamId,next.toString())).use { row ->
                if(row.moveToFirst())row.getString(0) else null
            }
            if(state!="ACKNOWLEDGED")break
            current=next
        }
        db.execSQL("UPDATE t043_sync_cursors SET last_contiguous_no=?,updated_at_ms=? " +
            "WHERE sender_id=? AND recipient_id=? AND stream_id=? AND direction='OUTBOUND'",
            arrayOf(current,ms,e.senderId,e.recipientId,e.streamId))
    }
}
