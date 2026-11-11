package com.example.airemote

import android.database.sqlite.SQLiteDatabase

/** T043 SQLite v2. Additive-only DDL. Called inside SQLiteOpenHelper's own transaction.
 * NEVER wrap onCreate/onUpgrade in beginTransaction(). */
internal object T043SqliteV2Schema {
    internal val ddl: List<String> = listOf(
        """CREATE TABLE t043_sources (
 namespace TEXT PRIMARY KEY NOT NULL,
 first_seen_ms INTEGER NOT NULL CHECK(first_seen_ms >= 0)
)""",
        """CREATE TABLE t043_observations (
 observation_key TEXT PRIMARY KEY NOT NULL,
 namespace TEXT NOT NULL REFERENCES t043_sources(namespace) ON DELETE RESTRICT,
 ruleset_id TEXT NOT NULL,
 observation_id TEXT NOT NULL,
 schema_major INTEGER NOT NULL CHECK(schema_major = 1),
 schema_minor INTEGER NOT NULL CHECK(schema_minor >= 0),
 payload_json TEXT,
 payload_bytes BLOB NOT NULL CHECK(typeof(payload_bytes)='blob'),
 raw_sha256 TEXT NOT NULL CHECK(length(raw_sha256)=64),
 semantic_sha256 TEXT CHECK(semantic_sha256 IS NULL OR length(semantic_sha256)=64),
 comparison_mode TEXT NOT NULL CHECK(comparison_mode IN ('SEMANTIC_JSON','RAW_BYTES')),
 canonicalization_id TEXT NOT NULL,
 stored_ms INTEGER NOT NULL CHECK(stored_ms >= 0)
)""",
        """CREATE INDEX t043_obs_source_ruleset ON t043_observations(namespace,ruleset_id,stored_ms)""",
        """CREATE TABLE t043_verified_hands (
 hand_record_id TEXT PRIMARY KEY NOT NULL,
 namespace TEXT NOT NULL REFERENCES t043_sources(namespace) ON DELETE RESTRICT,
 ruleset_id TEXT NOT NULL CHECK(ruleset_id IN (
 'holdem.standard52.no-limit.v1','holdem.standard52.pot-limit.v1','holdem.standard52.fixed-limit.v1')),
 verification_policy_id TEXT NOT NULL CHECK(verification_policy_id='independent-full-record.v1'),
 schema_major INTEGER NOT NULL CHECK(schema_major=1),
 schema_minor INTEGER NOT NULL CHECK(schema_minor=0),
 payload_json TEXT NOT NULL,
 payload_bytes BLOB NOT NULL CHECK(typeof(payload_bytes)='blob'),
 payload_sha256 TEXT NOT NULL CHECK(length(payload_sha256)=64),
 verified_at_ms INTEGER NOT NULL CHECK(verified_at_ms >= 0),
 UNIQUE(hand_record_id,ruleset_id)
)""",
        """CREATE INDEX t043_hands_ruleset_time ON t043_verified_hands(ruleset_id,verified_at_ms)""",
        """CREATE TABLE t043_verified_events (
 event_key TEXT PRIMARY KEY NOT NULL,
 hand_record_id TEXT NOT NULL,
 ruleset_id TEXT NOT NULL,
 event_id TEXT NOT NULL,
 seq INTEGER NOT NULL CHECK(seq>=0),
 evidence_level TEXT NOT NULL CHECK(evidence_level='INDEPENDENT'),
 payload_json TEXT NOT NULL,
 payload_sha256 TEXT NOT NULL CHECK(length(payload_sha256)=64),
 UNIQUE(hand_record_id,event_id), UNIQUE(hand_record_id,seq),
 FOREIGN KEY(hand_record_id,ruleset_id) REFERENCES t043_verified_hands(hand_record_id,ruleset_id) ON DELETE RESTRICT
)""",
        """CREATE TABLE t043_conflicts (
 id INTEGER PRIMARY KEY,
 entity_type TEXT NOT NULL CHECK(entity_type IN ('observation','event','hand','sync_outbox','sync_inbox')),
 identity_key TEXT NOT NULL CHECK(length(identity_key)>0),
 stored_envelope_sha256 TEXT NOT NULL CHECK(length(stored_envelope_sha256)=64),
 incoming_envelope_sha256 TEXT NOT NULL CHECK(length(incoming_envelope_sha256)=64),
 reason TEXT NOT NULL CHECK(length(reason)>0),
 detected_at_ms INTEGER NOT NULL CHECK(detected_at_ms>=0),
 UNIQUE(entity_type,identity_key,incoming_envelope_sha256)
)""",
        """CREATE INDEX t043_conflicts_lookup ON t043_conflicts(entity_type,identity_key)""",
        """CREATE TABLE t043_sync_outbox (
 sender_id TEXT NOT NULL CHECK(length(sender_id)>0),
 message_id TEXT NOT NULL CHECK(length(message_id)>0),
 recipient_id TEXT NOT NULL CHECK(length(recipient_id)>0),
 stream_id TEXT NOT NULL CHECK(length(stream_id)>0),
 sequence_no INTEGER NOT NULL CHECK(typeof(sequence_no)='integer' AND sequence_no>0),
 content_type TEXT NOT NULL CHECK(length(content_type)>0),
 source_namespace TEXT REFERENCES t043_sources(namespace) ON DELETE RESTRICT,
 related_record_type TEXT, related_record_key TEXT, ruleset_id TEXT,
 payload_encoding TEXT NOT NULL CHECK(payload_encoding IN ('JSON_UTF8','OPAQUE_BYTES')),
 payload_schema_major INTEGER, payload_schema_minor INTEGER,
 canonicalization_id TEXT NOT NULL CHECK(length(canonicalization_id)>0),
 comparison_mode TEXT NOT NULL CHECK(comparison_mode IN ('SEMANTIC_JSON','RAW_BYTES')),
 payload_bytes BLOB NOT NULL CHECK(typeof(payload_bytes)='blob'),
 raw_sha256 TEXT NOT NULL CHECK(length(raw_sha256)=64),
 semantic_sha256 TEXT CHECK(semantic_sha256 IS NULL OR length(semantic_sha256)=64),
 envelope_sha256 TEXT NOT NULL CHECK(length(envelope_sha256)=64),
 delivery_state TEXT NOT NULL DEFAULT 'PENDING' CHECK(delivery_state IN (
 'PENDING','LEASED','SENT_UNCONFIRMED','RETRY','ACKNOWLEDGED','DEAD_LETTER','QUARANTINED')),
 attempts INTEGER NOT NULL DEFAULT 0 CHECK(attempts>=0),
 created_at_ms INTEGER NOT NULL CHECK(created_at_ms>=0),
 next_retry_at_ms INTEGER CHECK(next_retry_at_ms IS NULL OR next_retry_at_ms>=0),
 lease_token TEXT, lease_expires_at_ms INTEGER CHECK(lease_expires_at_ms IS NULL OR lease_expires_at_ms>=0),
 sent_at_ms INTEGER CHECK(sent_at_ms IS NULL OR sent_at_ms>=0),
 acknowledged_at_ms INTEGER CHECK(acknowledged_at_ms IS NULL OR acknowledged_at_ms>=0),
 acknowledgement_ref TEXT, last_error_code TEXT,
 PRIMARY KEY(sender_id,message_id),
 UNIQUE(sender_id,recipient_id,stream_id,sequence_no),
 CHECK((related_record_type IS NULL)=(related_record_key IS NULL)),
 CHECK((payload_schema_major IS NULL)=(payload_schema_minor IS NULL)),
 CHECK(payload_schema_major IS NULL OR payload_schema_major>0),
 CHECK(payload_schema_minor IS NULL OR payload_schema_minor>=0),
 CHECK((comparison_mode='SEMANTIC_JSON' AND semantic_sha256 IS NOT NULL
  AND payload_encoding='JSON_UTF8' AND canonicalization_id='json-semantic.v1')
  OR (comparison_mode='RAW_BYTES' AND semantic_sha256 IS NULL)),
 CHECK(comparison_mode<>'RAW_BYTES' OR
   (payload_encoding='OPAQUE_BYTES' AND canonicalization_id='raw-bytes.v1')
   OR delivery_state='QUARANTINED'),
 CHECK(delivery_state<>'LEASED' OR (lease_token IS NOT NULL AND lease_expires_at_ms IS NOT NULL)),
 CHECK(delivery_state<>'ACKNOWLEDGED' OR
   (acknowledged_at_ms IS NOT NULL AND acknowledgement_ref IS NOT NULL))
)""",
        """CREATE INDEX t043_outbox_stream_queue ON t043_sync_outbox(sender_id,recipient_id,stream_id,delivery_state,sequence_no)""",
        """CREATE INDEX t043_outbox_due ON t043_sync_outbox(delivery_state,next_retry_at_ms,created_at_ms)""",
        """CREATE TABLE t043_sync_inbox (
 sender_id TEXT NOT NULL CHECK(length(sender_id)>0),
 message_id TEXT NOT NULL CHECK(length(message_id)>0),
 recipient_id TEXT NOT NULL CHECK(length(recipient_id)>0),
 stream_id TEXT NOT NULL CHECK(length(stream_id)>0),
 sequence_no INTEGER NOT NULL CHECK(typeof(sequence_no)='integer' AND sequence_no>0),
 content_type TEXT NOT NULL CHECK(length(content_type)>0),
 source_namespace TEXT REFERENCES t043_sources(namespace) ON DELETE RESTRICT,
 related_record_type TEXT, related_record_key TEXT, ruleset_id TEXT,
 payload_encoding TEXT NOT NULL CHECK(payload_encoding IN ('JSON_UTF8','OPAQUE_BYTES')),
 payload_schema_major INTEGER, payload_schema_minor INTEGER,
 canonicalization_id TEXT NOT NULL CHECK(length(canonicalization_id)>0),
 comparison_mode TEXT NOT NULL CHECK(comparison_mode IN ('SEMANTIC_JSON','RAW_BYTES')),
 payload_bytes BLOB NOT NULL CHECK(typeof(payload_bytes)='blob'),
 raw_sha256 TEXT NOT NULL CHECK(length(raw_sha256)=64),
 semantic_sha256 TEXT CHECK(semantic_sha256 IS NULL OR length(semantic_sha256)=64),
 envelope_sha256 TEXT NOT NULL CHECK(length(envelope_sha256)=64),
 processing_state TEXT NOT NULL DEFAULT 'RECEIVED' CHECK(processing_state IN
  ('RECEIVED','PROCESSING','APPLIED','QUARANTINED','REJECTED')),
 received_at_ms INTEGER NOT NULL CHECK(received_at_ms>=0),
 attempts INTEGER NOT NULL DEFAULT 0 CHECK(attempts>=0),
 lease_token TEXT, lease_expires_at_ms INTEGER CHECK(lease_expires_at_ms IS NULL OR lease_expires_at_ms>=0),
 applied_at_ms INTEGER CHECK(applied_at_ms IS NULL OR applied_at_ms>=0),
 last_error_code TEXT,
 PRIMARY KEY(sender_id,message_id),
 UNIQUE(sender_id,recipient_id,stream_id,sequence_no),
 CHECK((related_record_type IS NULL)=(related_record_key IS NULL)),
 CHECK((payload_schema_major IS NULL)=(payload_schema_minor IS NULL)),
 CHECK(payload_schema_major IS NULL OR payload_schema_major>0),
 CHECK(payload_schema_minor IS NULL OR payload_schema_minor>=0),
 CHECK((comparison_mode='SEMANTIC_JSON' AND semantic_sha256 IS NOT NULL
  AND payload_encoding='JSON_UTF8' AND canonicalization_id='json-semantic.v1')
  OR (comparison_mode='RAW_BYTES' AND semantic_sha256 IS NULL)),
 CHECK(comparison_mode<>'RAW_BYTES' OR
  (payload_encoding='OPAQUE_BYTES' AND canonicalization_id='raw-bytes.v1')
  OR processing_state='QUARANTINED'),
 CHECK(processing_state<>'PROCESSING' OR
  (lease_token IS NOT NULL AND lease_expires_at_ms IS NOT NULL)),
 CHECK(processing_state<>'APPLIED' OR applied_at_ms IS NOT NULL)
)""",
        """CREATE INDEX t043_inbox_stream ON t043_sync_inbox(sender_id,recipient_id,stream_id,sequence_no)""",
        """CREATE INDEX t043_inbox_pending ON t043_sync_inbox(processing_state,received_at_ms)""",
        """CREATE TABLE t043_sync_cursors (
 sender_id TEXT NOT NULL CHECK(length(sender_id)>0),
 recipient_id TEXT NOT NULL CHECK(length(recipient_id)>0),
 stream_id TEXT NOT NULL CHECK(length(stream_id)>0),
 direction TEXT NOT NULL CHECK(direction IN ('INBOUND','OUTBOUND')),
 initial_checkpoint_no INTEGER NOT NULL DEFAULT 0 CHECK(initial_checkpoint_no=0),
 initial_checkpoint_proof_ref TEXT,
 last_contiguous_no INTEGER NOT NULL DEFAULT 0 CHECK(last_contiguous_no>=0),
 max_seen_no INTEGER NOT NULL DEFAULT 0 CHECK(max_seen_no>=0),
 last_allocated_no INTEGER NOT NULL DEFAULT 0 CHECK(last_allocated_no>=0),
 updated_at_ms INTEGER NOT NULL CHECK(updated_at_ms>=0),
 PRIMARY KEY(sender_id,recipient_id,stream_id,direction),
 CHECK(initial_checkpoint_proof_ref IS NULL),
 CHECK(max_seen_no>=last_contiguous_no),
 CHECK(direction<>'INBOUND' OR last_allocated_no=0),
 CHECK(direction<>'OUTBOUND' OR max_seen_no=last_allocated_no)
)""",
        """CREATE TABLE t043_model_registry (
 model_id TEXT PRIMARY KEY NOT NULL,
 ruleset_id TEXT NOT NULL,
 model_version TEXT NOT NULL,
 artifact_sha256 TEXT CHECK(artifact_sha256 IS NULL OR length(artifact_sha256)=64),
 lifecycle TEXT NOT NULL DEFAULT 'REGISTERED' CHECK(lifecycle IN ('REGISTERED','RETIRED')),
 created_ms INTEGER NOT NULL CHECK(created_ms>=0),
 UNIQUE(model_id,ruleset_id)
)""",
        """CREATE TABLE t043_training_metadata (
 dataset_id TEXT PRIMARY KEY NOT NULL,
 model_id TEXT,
 ruleset_id TEXT NOT NULL CHECK(ruleset_id IN (
 'holdem.standard52.no-limit.v1','holdem.standard52.pot-limit.v1','holdem.standard52.fixed-limit.v1')),
 verification_policy_id TEXT NOT NULL CHECK(verification_policy_id='independent-full-record.v1'),
 schema_major INTEGER NOT NULL CHECK(schema_major=1),
 schema_minor INTEGER NOT NULL CHECK(schema_minor=0),
 metadata_json TEXT NOT NULL DEFAULT '{}',
 created_ms INTEGER NOT NULL CHECK(created_ms>=0),
 UNIQUE(dataset_id,ruleset_id),
 FOREIGN KEY(model_id,ruleset_id) REFERENCES t043_model_registry(model_id,ruleset_id) ON DELETE RESTRICT
)""",
        """CREATE TABLE t043_training_members (
 dataset_id TEXT NOT NULL, hand_record_id TEXT NOT NULL, ruleset_id TEXT NOT NULL,
 PRIMARY KEY(dataset_id,hand_record_id),
 FOREIGN KEY(dataset_id,ruleset_id) REFERENCES t043_training_metadata(dataset_id,ruleset_id) ON DELETE RESTRICT,
 FOREIGN KEY(hand_record_id,ruleset_id) REFERENCES t043_verified_hands(hand_record_id,ruleset_id) ON DELETE RESTRICT
)""",
        """CREATE INDEX t043_training_by_hand ON t043_training_members(hand_record_id)""",
        """CREATE TRIGGER t043_outbox_immutable BEFORE UPDATE OF sender_id,message_id,recipient_id,stream_id,sequence_no,content_type,source_namespace,related_record_type,related_record_key,ruleset_id,payload_encoding,payload_schema_major,payload_schema_minor,canonicalization_id,comparison_mode,payload_bytes,raw_sha256,semantic_sha256,envelope_sha256 ON t043_sync_outbox BEGIN SELECT RAISE(ABORT,'immutable outbox message'); END""",
        """CREATE TRIGGER t043_inbox_immutable BEFORE UPDATE OF sender_id,message_id,recipient_id,stream_id,sequence_no,content_type,source_namespace,related_record_type,related_record_key,ruleset_id,payload_encoding,payload_schema_major,payload_schema_minor,canonicalization_id,comparison_mode,payload_bytes,raw_sha256,semantic_sha256,envelope_sha256 ON t043_sync_inbox BEGIN SELECT RAISE(ABORT,'immutable inbox message'); END""",
        """CREATE TRIGGER t043_cursors_identity_immutable BEFORE UPDATE OF sender_id,recipient_id,stream_id,direction,initial_checkpoint_no,initial_checkpoint_proof_ref ON t043_sync_cursors BEGIN SELECT RAISE(ABORT,'immutable cursor identity/checkpoint'); END""",
        """CREATE TRIGGER t043_cursors_monotonic BEFORE UPDATE OF last_contiguous_no,max_seen_no,last_allocated_no ON t043_sync_cursors WHEN NEW.last_contiguous_no<OLD.last_contiguous_no OR NEW.max_seen_no<OLD.max_seen_no OR NEW.last_allocated_no<OLD.last_allocated_no BEGIN SELECT RAISE(ABORT,'cursor regression'); END""",
        """CREATE TRIGGER t043_cursors_no_gaps_inbound BEFORE UPDATE OF last_contiguous_no ON t043_sync_cursors WHEN NEW.direction='INBOUND' AND NEW.last_contiguous_no>OLD.last_contiguous_no AND (SELECT COUNT(*) FROM t043_sync_inbox m WHERE m.sender_id=NEW.sender_id AND m.recipient_id=NEW.recipient_id AND m.stream_id=NEW.stream_id AND m.sequence_no>OLD.last_contiguous_no AND m.sequence_no<=NEW.last_contiguous_no AND m.processing_state='APPLIED')<>(NEW.last_contiguous_no-OLD.last_contiguous_no) BEGIN SELECT RAISE(ABORT,'gap or unprocessed incoming message'); END""",
        """CREATE TRIGGER t043_cursors_no_gaps_outbound BEFORE UPDATE OF last_contiguous_no ON t043_sync_cursors WHEN NEW.direction='OUTBOUND' AND NEW.last_contiguous_no>OLD.last_contiguous_no AND (SELECT COUNT(*) FROM t043_sync_outbox m WHERE m.sender_id=NEW.sender_id AND m.recipient_id=NEW.recipient_id AND m.stream_id=NEW.stream_id AND m.sequence_no>OLD.last_contiguous_no AND m.sequence_no<=NEW.last_contiguous_no AND m.delivery_state='ACKNOWLEDGED')<>(NEW.last_contiguous_no-OLD.last_contiguous_no) BEGIN SELECT RAISE(ABORT,'gap or unacknowledged outgoing message'); END""",
        """CREATE TRIGGER t043_inbox_applied_terminal BEFORE UPDATE ON t043_sync_inbox WHEN OLD.processing_state='APPLIED' AND (NEW.processing_state<>OLD.processing_state OR NEW.applied_at_ms IS NOT OLD.applied_at_ms) BEGIN SELECT RAISE(ABORT,'applied inbox terminal'); END""",
        """CREATE TRIGGER t043_outbox_ack_terminal BEFORE UPDATE ON t043_sync_outbox WHEN OLD.delivery_state='ACKNOWLEDGED' AND (NEW.delivery_state<>OLD.delivery_state OR NEW.acknowledged_at_ms IS NOT OLD.acknowledged_at_ms OR NEW.acknowledgement_ref IS NOT OLD.acknowledgement_ref) BEGIN SELECT RAISE(ABORT,'acknowledged outbox terminal'); END""",
        """CREATE TRIGGER t043_inbox_no_delete BEFORE DELETE ON t043_sync_inbox BEGIN SELECT RAISE(ABORT,'inbox retention'); END""",
        """CREATE TRIGGER t043_outbox_no_delete BEFORE DELETE ON t043_sync_outbox BEGIN SELECT RAISE(ABORT,'outbox retention'); END""",
        """CREATE TRIGGER t043_verified_hands_immutable BEFORE UPDATE ON t043_verified_hands BEGIN SELECT RAISE(ABORT,'verified hand immutable'); END""",
        """CREATE TRIGGER t043_verified_hands_no_delete BEFORE DELETE ON t043_verified_hands BEGIN SELECT RAISE(ABORT,'verified hand retention'); END""",
        """CREATE TRIGGER t043_verified_events_immutable BEFORE UPDATE ON t043_verified_events BEGIN SELECT RAISE(ABORT,'verified event immutable'); END""",
        """CREATE TRIGGER t043_verified_events_no_delete BEFORE DELETE ON t043_verified_events BEGIN SELECT RAISE(ABORT,'verified event retention'); END"""
    )
    fun install(db: SQLiteDatabase) {
        ddl.forEach(db::execSQL)
    }
}
