package com.example.airemote

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

enum class T043WriteResult { APPLIED, ALREADY_SEEN, CONFLICT, REJECTED, STORAGE_ERROR }

/** An independent proof implementation must authenticate the pinned, immutable byte snapshot
 * and the digest it is asked to certify. There is intentionally no production implementation. */
fun interface T043ImmutableProofVerifier {
    fun verifySnapshot(snapshot: ByteArray, sha256: String, record: VerifiedPokerHandRecord): Boolean
}

data class T043SyncDraft(
    val senderId: String, val messageId: String, val recipientId: String,
    val streamId: String, val contentType: String,
    val sourceNamespace: String? = null,
    val relatedRecordType: String? = null,
    val relatedRecordKey: String? = null,
    val rulesetId: String? = null,
    val payloadEncoding: String = "JSON_UTF8",
    val schemaMajor: Int? = null, val schemaMinor: Int? = null,
    val canonicalizationId: String = T043MessageFingerprint.JSON_CANONICALIZATION
) {
    fun header(sequenceNo: Long) = T043MessageFingerprint.Envelope(
        senderId,messageId,recipientId,streamId,sequenceNo,contentType,
        sourceNamespace,relatedRecordType,relatedRecordKey,rulesetId,
        payloadEncoding,schemaMajor,schemaMinor,canonicalizationId
    )
}

/** SQLite v2's opt-in, local-only companion for the existing AndroidHandLearningStore.
 * No FTP, model trainer, OCR, strategy, or action controller is connected.
 * Use the SAME helper instance as the unchanged legacy PostHandLearner. */
class T043CanonicalSqliteStore(private val helper: AndroidHandLearningStore) {
    private val supported = PokerRulesets.supportedForVerification
    private val lastSeq = Long.MAX_VALUE

    private fun now() = System.currentTimeMillis()
    private fun decodeUtf8(raw: ByteArray): String =
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(raw)).toString()

    private fun source(db: SQLiteDatabase, namespace: String, ms: Long) {
        val values = ContentValues().apply {
            put("namespace",namespace);put("first_seen_ms",ms)
        }
        db.insertWithOnConflict("t043_sources",null,values,SQLiteDatabase.CONFLICT_IGNORE)
    }
    private fun singleText(db: SQLiteDatabase, sql: String, vararg args: String): String? =
        db.rawQuery(sql,args).use { if (it.moveToFirst()) it.getString(0) else null }

    private fun conflict(db: SQLiteDatabase, entity: String, key: String,
                         oldDigest: String, newDigest: String, reason: String, ms: Long) {
        val v = ContentValues().apply {
            put("entity_type",entity);put("identity_key",key)
            put("stored_envelope_sha256",oldDigest);put("incoming_envelope_sha256",newDigest)
            put("reason",reason);put("detected_at_ms",ms)
        }
        db.insertWithOnConflict("t043_conflicts",null,v,SQLiteDatabase.CONFLICT_IGNORE)
    }
    private inline fun write(body: (SQLiteDatabase)->T043WriteResult): T043WriteResult {
        return try {
            val db = helper.writableDatabase
            db.beginTransaction()
            try {
                val result = body(db)
                db.setTransactionSuccessful()
                result
            } finally { db.endTransaction() }
        } catch (_: Exception) { T043WriteResult.STORAGE_ERROR }
    }
    private fun insert(db: SQLiteDatabase, table: String, values: ContentValues) {
        require(db.insertOrThrow(table,null,values) >= 0) { "SQLite insertion failed" }
    }
    private fun digest(raw: ByteArray, encoding: String, algorithm: String) =
        T043MessageFingerprint.digest(raw,encoding,algorithm)

    private fun comparable(d: T043MessageFingerprint.Digest) = d.comparisonDigest

    /** Observation import stores ORIGINAL bytes. Future schema minor is diagnostic-only.
     * The caller MUST supply the real upstream observation ID; no fabricated IDs. */
    @Synchronized fun importObservation(rawBytes: ByteArray): T043WriteResult {
        val raw = rawBytes.copyOf()
        val d = digest(raw,"JSON_UTF8",T043MessageFingerprint.JSON_CANONICALIZATION)
        if (d.quarantine) return T043WriteResult.REJECTED
        val observation = try { CanonicalPokerJson.decodeObservation(decodeUtf8(raw)) }
            catch (_: Exception) { return T043WriteResult.REJECTED }
        val key = observation.stableObservationKey() ?: return T043WriteResult.REJECTED
        val incoming = comparable(d)
        return write { db ->
            db.execSQL("SAVEPOINT t043_import")
            val previous = singleText(db,
                "SELECT COALESCE(semantic_sha256,raw_sha256) FROM t043_observations WHERE observation_key=?",
                key)
            if (previous != null) {
                db.execSQL("ROLLBACK TO SAVEPOINT t043_import")
                db.execSQL("RELEASE SAVEPOINT t043_import")
                if (previous == incoming) T043WriteResult.ALREADY_SEEN
                else {
                    conflict(db,"observation",key,previous,incoming,"IDENTITY_CONTENT_CHANGED",now())
                    T043WriteResult.CONFLICT
                }
            } else {
                val ms = now()
                source(db,observation.source.namespace,ms)
                val v = ContentValues().apply {
                    put("observation_key",key)
                    put("namespace",observation.source.namespace)
                    put("ruleset_id",observation.rulesetId)
                    put("observation_id",observation.observationId)
                    put("schema_major",observation.schemaMajor)
                    put("schema_minor",observation.schemaMinor)
                    put("payload_json",decodeUtf8(raw))
                    put("payload_bytes",raw)
                    put("raw_sha256",d.rawSha256)
                    put("semantic_sha256",d.semanticSha256)
                    put("comparison_mode",d.comparisonMode)
                    put("canonicalization_id",T043MessageFingerprint.JSON_CANONICALIZATION)
                    put("stored_ms",ms)
                }
                insert(db,"t043_observations",v)
                db.execSQL("RELEASE SAVEPOINT t043_import")
                T043WriteResult.APPLIED
            }
        }
    }

    /** Fail closed unless BOTH the Stage-1 policy gate and a trusted verifier bound
     * to the EXACT byte snapshot succeed. No verifier is installed by default. */
    @Synchronized fun importVerifiedHand(rawBytes: ByteArray,
        modelVerifier: PokerHandProofVerifier?,
        bytesVerifier: T043ImmutableProofVerifier?): T043WriteResult {
        if (modelVerifier == null || bytesVerifier == null) return T043WriteResult.REJECTED
        val snapshot = rawBytes.copyOf()
        val d = digest(snapshot,"JSON_UTF8",T043MessageFingerprint.JSON_CANONICALIZATION)
        if (d.quarantine) return T043WriteResult.REJECTED
        val record = try { CanonicalPokerJson.decodeVerifiedHand(decodeUtf8(snapshot)) }
            catch (_: Exception) { return T043WriteResult.REJECTED }
        if (PokerHandVerificationGate.verify(record,modelVerifier) != PokerHandVerificationStatus.VERIFIED ||
            record.rulesetId !in supported ||
            record.schemaMinor != CanonicalPokerSchema.MINOR ||
            record.terminalObservation.schemaMinor != CanonicalPokerSchema.MINOR)
            return T043WriteResult.REJECTED
        // A Stage-1 decoder cannot silently drop future/unknown fields from a trusted snapshot.
        val roundTrip = digest(CanonicalPokerJson.encodeVerifiedHand(record).toByteArray(
            StandardCharsets.UTF_8),"JSON_UTF8",T043MessageFingerprint.JSON_CANONICALIZATION)
        if (roundTrip.semanticSha256 != d.semanticSha256) return T043WriteResult.REJECTED
        val verified = try {
            bytesVerifier.verifySnapshot(snapshot.copyOf(),d.rawSha256,record)
        } catch (_: Exception) { false }
        if (!verified || T043MessageFingerprint.sha256(snapshot) != d.rawSha256)
            return T043WriteResult.REJECTED
        val handId = record.handRecordId
        val incoming = comparable(d)
        return write { db ->
            db.execSQL("SAVEPOINT t043_import")
            val previous = singleText(db,
                "SELECT payload_sha256 FROM t043_verified_hands WHERE hand_record_id=?",handId)
            if (previous != null) {
                db.execSQL("ROLLBACK TO SAVEPOINT t043_import")
                db.execSQL("RELEASE SAVEPOINT t043_import")
                if (previous == incoming) T043WriteResult.ALREADY_SEEN
                else {
                    conflict(db,"hand",handId,previous,incoming,"VERIFIED_HAND_CHANGED",now())
                    T043WriteResult.CONFLICT
                }
            } else {
                val ms = now()
                source(db,record.terminalObservation.source.namespace,ms)
                val v = ContentValues().apply {
                    put("hand_record_id",handId)
                    put("namespace",record.terminalObservation.source.namespace)
                    put("ruleset_id",record.rulesetId)
                    put("verification_policy_id",record.verificationPolicyId)
                    put("schema_major",record.schemaMajor);put("schema_minor",record.schemaMinor)
                    put("payload_json",decodeUtf8(snapshot))
                    put("payload_bytes",snapshot)
                    put("payload_sha256",incoming)
                    put("verified_at_ms",ms)
                }
                insert(db,"t043_verified_hands",v)
                // Only independently corroborated actions qualify for the verified event history.
                record.terminalObservation.actions.filter { it.evidence == PokerEvidence.INDEPENDENT }
                    .forEach { action ->
                        val eventKey = requireNotNull(record.terminalObservation.stableEventKey(action.eventId))
                        val a = ContentValues().apply {
                            put("event_key",eventKey)
                            put("hand_record_id",handId)
                            put("ruleset_id",record.rulesetId)
                            put("event_id",action.eventId)
                            put("seq",action.sequence)
                            put("evidence_level","INDEPENDENT")
                            // Preserve the normalized event, not merely its identity.
                            val actionJson = org.json.JSONObject()
                                .put("eventId",action.eventId)
                                .put("sequence",action.sequence)
                                .put("street",action.street.name)
                                .put("actorSeat",action.actorSeat ?: org.json.JSONObject.NULL)
                                .put("kind",action.kind.name)
                                .put("amountChips",action.amountChips ?: org.json.JSONObject.NULL)
                                .put("evidence",action.evidence.name).toString()
                            put("payload_json",actionJson)
                            put("payload_sha256",T043MessageFingerprint.digest(
                                actionJson.toByteArray(StandardCharsets.UTF_8),
                                "JSON_UTF8",T043MessageFingerprint.JSON_CANONICALIZATION
                            ).comparisonDigest)
                        }
                        insert(db,"t043_verified_events",a)
                    }
                db.execSQL("RELEASE SAVEPOINT t043_import")
                T043WriteResult.APPLIED
            }
        }
    }

    /** Allocate stream sequence + insert outbox message in ONE SQLite write transaction. */
    @Synchronized fun enqueue(draft: T043SyncDraft, rawBytes: ByteArray): T043WriteResult {
        val snapshot = rawBytes.copyOf()
        val computed = digest(snapshot,draft.payloadEncoding,draft.canonicalizationId)
        val restricted = computed.quarantine ||
            (draft.schemaMajor != null &&
             (draft.schemaMajor != 1 || draft.schemaMinor != 0))
        return write { db ->
            db.execSQL("SAVEPOINT t043_import")
            val prior = db.rawQuery(
                "SELECT sequence_no,envelope_sha256 FROM t043_sync_outbox WHERE sender_id=? AND message_id=?",
                arrayOf(draft.senderId,draft.messageId)
            ).use { c ->
                if (c.moveToFirst()) Pair(c.getLong(0),c.getString(1)) else null
            }
            if (prior != null) {
                val candidateHash = T043MessageFingerprint.envelopeSha256(
                    draft.header(prior.first),computed)
                db.execSQL("ROLLBACK TO SAVEPOINT t043_import")
                db.execSQL("RELEASE SAVEPOINT t043_import")
                if (prior.second == candidateHash) T043WriteResult.ALREADY_SEEN
                else {
                    conflict(db,"sync_outbox",draft.senderId+":"+draft.messageId,
                        prior.second,candidateHash,"MESSAGE_ID_REUSED",now())
                    T043WriteResult.CONFLICT
                }
            } else {
                val ms = now()
                val start = ContentValues().apply {
                    put("sender_id",draft.senderId);put("recipient_id",draft.recipientId)
                    put("stream_id",draft.streamId);put("direction","OUTBOUND")
                    put("updated_at_ms",ms)
                }
                db.insertWithOnConflict("t043_sync_cursors",null,start,
                    SQLiteDatabase.CONFLICT_IGNORE)
                val allocated = db.rawQuery(
                    "SELECT last_allocated_no FROM t043_sync_cursors WHERE sender_id=? AND recipient_id=? AND stream_id=? AND direction='OUTBOUND'",
                    arrayOf(draft.senderId,draft.recipientId,draft.streamId)
                ).use { c -> check(c.moveToFirst());c.getLong(0) }
                check(allocated < lastSeq) { "Sequence overflow" }
                val sequence = allocated + 1
                db.execSQL("UPDATE t043_sync_cursors SET last_allocated_no=?, max_seen_no=?,updated_at_ms=? WHERE sender_id=? AND recipient_id=? AND stream_id=? AND direction='OUTBOUND'",
                    arrayOf(sequence,sequence,ms,draft.senderId,draft.recipientId,draft.streamId))
                val header = draft.header(sequence)
                val envelope = T043MessageFingerprint.envelopeSha256(header,computed)
                if (draft.sourceNamespace != null) source(db,draft.sourceNamespace,ms)
                insert(db,"t043_sync_outbox",messageValues(header,computed,snapshot,envelope,ms,
                    if (restricted) "QUARANTINED" else "PENDING",outbox=true))
                db.execSQL("RELEASE SAVEPOINT t043_import")
                T043WriteResult.APPLIED
            }
        }
    }

    /** Local-only receive/store. No network receiver is connected. */
    @Synchronized fun receive(header: T043MessageFingerprint.Envelope,
                               rawBytes: ByteArray): T043WriteResult {
        val snapshot = rawBytes.copyOf()
        val computed = digest(snapshot,header.payloadEncoding,header.canonicalizationId)
        val envelope = T043MessageFingerprint.envelopeSha256(header,computed)
        val restricted = computed.quarantine ||
            (header.schemaMajor != null && (header.schemaMajor != 1 || header.schemaMinor != 0))
        return write { db ->
            db.execSQL("SAVEPOINT t043_import")
            val prior = singleText(db,
                "SELECT envelope_sha256 FROM t043_sync_inbox WHERE sender_id=? AND message_id=?",
                header.senderId,header.messageId)
            if (prior != null) {
                db.execSQL("ROLLBACK TO SAVEPOINT t043_import")
                db.execSQL("RELEASE SAVEPOINT t043_import")
                if (prior == envelope) T043WriteResult.ALREADY_SEEN
                else {
                    conflict(db,"sync_inbox",header.senderId+":"+header.messageId,
                        prior,envelope,"MESSAGE_ID_REUSED",now())
                    T043WriteResult.CONFLICT
                }
            } else {
                val ms = now()
                // Different message IDs cannot claim the same sender/recipient/stream/sequence.
                val occupied = singleText(db,
                    "SELECT envelope_sha256 FROM t043_sync_inbox WHERE sender_id=? AND recipient_id=? AND stream_id=? AND sequence_no=?",
                    header.senderId,header.recipientId,header.streamId,header.sequenceNo.toString())
                if (occupied != null) {
                    db.execSQL("ROLLBACK TO SAVEPOINT t043_import")
                    db.execSQL("RELEASE SAVEPOINT t043_import")
                    conflict(db,"sync_inbox",
                        header.senderId+":"+header.recipientId+":"+header.streamId+":"+header.sequenceNo,
                        occupied,envelope,"SEQUENCE_REUSED",ms)
                    T043WriteResult.CONFLICT
                } else {
                    val start = ContentValues().apply {
                        put("sender_id",header.senderId);put("recipient_id",header.recipientId)
                        put("stream_id",header.streamId);put("direction","INBOUND")
                        put("updated_at_ms",ms)
                    }
                    db.insertWithOnConflict("t043_sync_cursors",null,start,
                        SQLiteDatabase.CONFLICT_IGNORE)
                    db.execSQL("UPDATE t043_sync_cursors SET max_seen_no=MAX(max_seen_no,?),updated_at_ms=? WHERE sender_id=? AND recipient_id=? AND stream_id=? AND direction='INBOUND'",
                        arrayOf(header.sequenceNo,ms,header.senderId,header.recipientId,header.streamId))
                    if (header.sourceNamespace != null) source(db,header.sourceNamespace,ms)
                    insert(db,"t043_sync_inbox",messageValues(header,computed,snapshot,envelope,ms,
                        if (restricted) "QUARANTINED" else "RECEIVED",outbox=false))
                    db.execSQL("RELEASE SAVEPOINT t043_import")
                    T043WriteResult.APPLIED
                }
            }
        }
    }

    /** Applying a message, writing domain effects and moving the cursor form one transaction.
     * The caller is a trusted LOCAL processor, not a network or OCR listener.
     * A quarantine message can never be applied. */
    @Synchronized fun applyInboxAtomically(
        sender: String, message: String, domainChange: (SQLiteDatabase)->Unit
    ): T043WriteResult = write { db ->
        val details = db.rawQuery(
            "SELECT recipient_id,stream_id,sequence_no,processing_state FROM t043_sync_inbox WHERE sender_id=? AND message_id=?",
            arrayOf(sender,message)
        ).use { c ->
            if (!c.moveToFirst()) null else listOf(c.getString(0),c.getString(1),
                c.getLong(2).toString(),c.getString(3))
        } ?: return@write T043WriteResult.REJECTED
        if (details[3] == "APPLIED") return@write T043WriteResult.ALREADY_SEEN
        if (details[3] != "RECEIVED") return@write T043WriteResult.REJECTED
        var contiguous = db.rawQuery(
            "SELECT last_contiguous_no FROM t043_sync_cursors WHERE sender_id=? AND recipient_id=? AND stream_id=? AND direction='INBOUND'",
            arrayOf(sender,details[0],details[1])
        ).use { c -> check(c.moveToFirst());c.getLong(0) }
        // Domain effects are deliberately ordered. Later messages may be stored,
        // but must not be applied ahead of a missing or unprocessed predecessor.
        if (contiguous == Long.MAX_VALUE ||
            details[2].toLong() != contiguous + 1) return@write T043WriteResult.REJECTED
        domainChange(db)
        val ms = now()
        db.execSQL("UPDATE t043_sync_inbox SET processing_state='APPLIED',applied_at_ms=? WHERE sender_id=? AND message_id=? AND processing_state='RECEIVED'",
            arrayOf(ms,sender,message))
        while (contiguous < Long.MAX_VALUE) {
            val next = contiguous + 1
            val applied = singleText(db,
                "SELECT processing_state FROM t043_sync_inbox WHERE sender_id=? AND recipient_id=? AND stream_id=? AND sequence_no=?",
                sender,details[0],details[1],next.toString())
            if (applied != "APPLIED") break
            contiguous = next
        }
        db.execSQL("UPDATE t043_sync_cursors SET last_contiguous_no=?,updated_at_ms=? WHERE sender_id=? AND recipient_id=? AND stream_id=? AND direction='INBOUND'",
            arrayOf(contiguous,ms,sender,details[0],details[1]))
        T043WriteResult.APPLIED
    }

    /** No general-purpose ACK setter: only a future verified transport may acknowledge. */
    private fun messageValues(h: T043MessageFingerprint.Envelope,
        d: T043MessageFingerprint.Digest, payload: ByteArray, hash: String,
        ms: Long, state: String, outbox: Boolean): ContentValues = ContentValues().apply {
        put("sender_id",h.senderId);put("message_id",h.messageId)
        put("recipient_id",h.recipientId);put("stream_id",h.streamId)
        put("sequence_no",h.sequenceNo);put("content_type",h.contentType)
        put("source_namespace",h.sourceNamespace)
        put("related_record_type",h.relatedRecordType)
        put("related_record_key",h.relatedRecordKey)
        put("ruleset_id",h.rulesetId)
        put("payload_encoding",h.payloadEncoding)
        if (h.schemaMajor == null) putNull("payload_schema_major") else put("payload_schema_major",h.schemaMajor)
        if (h.schemaMinor == null) putNull("payload_schema_minor") else put("payload_schema_minor",h.schemaMinor)
        put("canonicalization_id",h.canonicalizationId)
        put("comparison_mode",d.comparisonMode)
        put("payload_bytes",payload)
        put("raw_sha256",d.rawSha256)
        put("semantic_sha256",d.semanticSha256)
        put("envelope_sha256",hash)
        if (outbox) {
            put("delivery_state",state);put("created_at_ms",ms)
        } else {
            put("processing_state",state);put("received_at_ms",ms)
        }
    }
}
