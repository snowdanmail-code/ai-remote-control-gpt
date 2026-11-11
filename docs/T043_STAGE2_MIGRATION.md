# T043 Stage 2 — SQLite v2 implementation and acceptance evidence

Base: `1fa50eab340329cd0e958172c795caefe5c73988` (T043 Stage 1 accepted).
Implementation branch: `feature/t043-sqlite-v2`. Do **not** merge PR #5 or build an APK.

## Storage migration

- File is unchanged: `hand_learning_v1.db`.
- SQLiteOpenHelper version increases from 1 to 2.
- The four original tables are kept byte-for-byte at the DDL source level:
  `completed_hands`, `opponents`, `hand_hero_actions`,
  `hand_opponent_facts`. Column order, positional INSERTs and existing
  `PostHandLearner` / `HandLearningStore` behavior are not changed.
- New `T043SqliteV2Schema.install` creates exactly 11 additive tables, their
  indexes, terminal-protection and cursor triggers.
- `SQLiteOpenHelper.onCreate` appends v2 installation after original v1 DDL.
  `onUpgrade(1,2)` installs only additive DDL. SQLiteOpenHelper already
  owns the schema transaction: **no nested beginTransaction** inside migration.
- `onConfigure` enables foreign key constraints before migration/usage.
- Any DDL failure aborts the helper's entire migration and does not commit
  user_version=2. Unknown upgrades fail explicitly.

## New tables

1. `t043_sources`: source namespace registry.
2. `t043_observations`: diagnostic observations with **original UTF-8 bytes**
   (including unknown future minor fields), independent of verified hand history.
3. `t043_verified_hands`: *trusted externally authenticated* hand receipts.
4. `t043_verified_events`: independent-evidence events with FK to verified hand
   *and its matching ruleset*.
5. `t043_conflicts`: append-only conflict journal (not overwritten originals).
6. `t043_sync_outbox`: sender-scoped message ID and per-stream monotonic number.
7. `t043_sync_inbox`: sender-scoped deduplication and out-of-order receipt.
8. `t043_sync_cursors`: sender + recipient + stream + direction cursor.
9. `t043_model_registry`: model metadata, no trainer.
10. `t043_training_metadata`: training dataset declaration by ruleset.
11. `t043_training_members`: prevents cross-ruleset membership through composite FKs.

No existing legacy hand is backfilled into verified history by guesswork.

## Proof boundaries and immutable imported data

`T043CanonicalSqliteStore` uses the **same helper instance** as the legacy store.
It is not wired into OCR, strategy, live game actions, FTP or ChatGPT.

- An incoming byte array is copied before hashing, parsing or external validation.
- Observations are diagnostic only; unsupported minor versions retain ORIGINAL
  JSON bytes, not a reserialized `org.json` representation.
- A verified hand requires the existing Stage-1 `PokerHandVerificationGate`,
  a caller-supplied independent model proof provider, **and** a trusted verifier
  authenticating exactly the pinned bytes plus their SHA-256. Neither provider
  has an installed production implementation; without both it fails closed.
- Stage-1 decode+re-encode must reproduce the same semantic JSON before
  a verified hand may be imported. This guards against silently dropped
  unknown security-relevant fields.
- `PokerEvidence.INDEPENDENT` and a reference string are *not proof*.
- Normalized confirmed events require evidence=INDEPENDENT, source-derived
  event ID and a parent hand; their content includes event kind, amount, street,
  actor and sequence, not just IDs.
- Verified tables reject direct SQL UPDATE/DELETE through SQLite triggers.
- Distinct rulesets cannot join a training dataset by composite foreign keys.

## Transaction and duplicate behavior

The local importer uses one SQLite write transaction per item. Before an
import it creates a `SAVEPOINT t043_import`; conflict branches roll back that
savepoint, then insert a conflict record **outside** the savepoint but within
the original transaction. The public result is `CONFLICT` only after commit.
If conflict recording fails, the result is `STORAGE_ERROR`, not a false success.

Identical ID + semantic content is `ALREADY_SEEN`; changed payload with
same ID is `CONFLICT` (original remains untouched). A SHA-256 digest is
an integrity marker, not cryptographic source authentication.

Each new outbound stream starts at checkpoint zero; nonzero production
checkpoint inserts are rejected by SQL constraint. The outgoing sequence
number is issued by incrementing the cursor and inserting the message
inside the same write transaction. Retries use the existing message and
sequence. Unique constraints guard same sender+messageID and same
sender+recipient+stream+sequence.

Inbound messages may arrive out of order. The contiguous cursor advances
only when each predecessor is applied. The cursor trigger blocks skipped
numbers; a trusted, local processing callback binds inbound domain changes
to the transition `RECEIVED -> APPLIED` and cursor update in one transaction.
Untrusted/quarantined records cannot be applied. There is no remote ACK
implementation; `SENT_UNCONFIRMED` does not mean delivery.

Terminal triggers prevent regression from `APPLIED` or `ACKNOWLEDGED`,
modification of their recorded times/receipts and DELETE of inbox/outbox.
The cursor's initial checkpoint and identity are immutable.

## Canonicalization

`T043MessageFingerprint` defines `json-semantic.v1`:
strict UTF-8, no BOM, duplicate key rejection, Unicode scalar validation,
byte-order sorting of decoded keys, exact base-10 normalization using
`BigInteger` (never floating point), and no unknown-field removal.
Malformatted or unknown-canonicalization JSON receives `RAW_BYTES` mode
and quarantine. Both raw and semantic SHA-256 plus the versioned,
domain-separated envelope SHA-256 are preserved.

All new `*_ms` values mean Unix epoch milliseconds. Original legacy time
values are never rewritten or silently reinterpreted.

## Verification suite

- `tools/test_t043_sqlite_v2.py`: 52 explicitly numbered Python sqlite3
  regressions: v1 preservation, v2 new database, atomic failed migration,
  simulated process crash, identical replays, conflicts, FKs, out-of-order
  delivery, and terminal mutation protections.
- `tools/T043FingerprintScenarios.kt`: 8 SHA test vectors and 4 envelope
  digest test vectors, numeric precision and quarantine.
- Existing Stage 0/1 Python/JVM/Android unit suites remain active.
- Actual Android instrumentation execution requires a device/emulator;
  Github CI currently compiles instrumentation but does not run an emulator.

## Out of scope

No server connectivity, FTP, Trainer, auto-retraining, APK, legacy table
rewrite, or intervention in OAuth, STOP, OCR or autonomous game control.
