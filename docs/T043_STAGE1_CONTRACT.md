# T043, Stage 1 — Canonical Texas Hold'em v1.0 (rework contract)

Scope: passive data contracts, synthetic sources and tests only. No production
source adapters, real action execution, authentication provider, SQLite/FTP,
Trainer, migration, or APK are added by this stage.

## Format version and compatibility

- Root `recordType` = `observation` or `verifiedHand`.
- `schemaMajor=1`, `schemaMinor=0` is the **first accepted wire contract**.
  Earlier PR #5 drafts before approval were experimental and are not supported
  input records. Their missing `rulesetId` / `eventId` / `handRecordId`
  MUST NOT be silently reconstructed or assigned synthetic IDs.
- Unknown major or negative minor => reject. Future `major=1,minor>0` can be
  parsed as **display/diagnostic data only**, discarding unknown optional JSON
  fields and mapping unknown enums to `UNKNOWN`. Re-encoding keeps the minor
  value, but may not preserve fields unknown to this reader. This is not a
  lossless forwarding codec.
- Trust-bearing operations **fail closed** for an unsupported minor version:
  `PokerHandVerificationGate.verify` returns `UNSUPPORTED_SCHEMA` if the
  record OR nested terminal observation minor is not exactly 0. A future-minor
  observation cannot yield a `stableParticipantKey` for learning.
- Minor revisions must not redefine any known field, remove a mandatory ID,
  or silently make a security-relevant field optional. Future critical changes
  need an explicitly implemented reader and new verification policy/version.
- JSON key order is unspecified; semantic round-trips are covered by tests.
  `CanonicalPokerJson.kt` alone imports Android `org.json`. Models, IDs,
  policy/verification interfaces and import classification are pure Kotlin.

## Mandatory rulesetId

- Every new `CanonicalPokerObservation` requires nonempty `rulesetId`,
  which is also copied into every `VerifiedPokerHandRecord` envelope.
  Root and nested values MUST match; mismatches are rejected on decode.
- Identifier syntax: `holdem.<deck>.<betting>.v<positive-integer>`, lowercase
  ASCII, length <= 128. Versions start at 1; no spaces or display nicknames.
- Explicit verification allowlist (no guessing from app/title/button layout):
  - `holdem.standard52.no-limit.v1`
  - `holdem.standard52.pot-limit.v1`
  - `holdem.standard52.fixed-limit.v1`
- Syntactically valid unknown variants are accepted as **observations** but
  return `UNSUPPORTED_RULESET` from the verification gate. Adding a new
  verified variant requires review and registry/policy changes.
- For learning, `rulesetId` is a required dataset partition. A stable opponent
  profile key includes ruleset ID; the same real hand ID replayed with a
  different ruleset is `CONFLICT`, not `DUPLICATE`. A training consumer must
  separately check the verification result and supported ruleset; an
  unverified/unknown ruleset is not eligible for training.

## Separate identities

- `observationId`: a source-assigned stable identifier for one logical
  **snapshot**. Replaying its bytes must not generate a fresh ID; later distinct
  snapshots get different observation IDs even if they show the same action.
- `eventId`: a source-assigned stable identifier for one **game action/event**.
  Mandatory for each `CanonicalPokerAction`; unique within an observation
  representing one hand. A new snapshot of the same action preserves its ID
  even if local frame/timestamp or observation ID changes. Neither the action
  `sequence` nor its screen frame is a valid substitute.
- `source.handId`: a stable source-authoritative hand identity, not a screen
  counter. With `namespace+sessionId+tableId+handId`, it yields the stable
  `handRecordId` for a completed-hand receipt. Verified record construction
  refuses incomplete identity. JSON persists `handRecordId` explicitly and
  rejects mismatch with the nested source scope.
- `source.namespace` identifies provider + tenant/instance and must not be
  a nickname. `stableParticipantKey` requires a verified participant ID and
  stable session, is scoped by namespace + ruleset + session + player ID, and
  does not equate users by matching nicknames.
- `PokerCanonicalIdentity` computes:
  - observation key: `observation:namespace/session/table/observationId`
  - event key: `event:namespace/session/table/handId/eventId`
  - hand key: `hand:namespace/session/table/handId`.
  Every component is UTF-8 byte-length-prefixed. Namespaces and keys are not
  inferred from screen coordinates or display names. Missing scope => null
  observation/event/hand key, hence `UNIDENTIFIABLE` for import where relevant.
- Stable keys are deterministic from immutable source identifiers across
  serialization, replay and process restart; the contract NEVER uses
  `hashCode`, system timestamps, random values, or memory-only counters.
  Sources must persist their own logical IDs across restarts. Determinism
  does not cryptographically authenticate a source.

## Observation and action details

- `tableCapacity` supports 2–9 seats; seats carry optional participant IDs,
  nickname, dealt-in/active tri-state, stack and up to two hole cards.
- `seatingComplete` determines whether active counts and positions may be
  inferred; unknown => no inference. Dealer/button positions wrap clockwise,
  including special heads-up BUTTON_SMALL_BLIND vs BIG_BLIND.
- Optional stage, board cards, pot and ordered action history. An action has
  `eventId`, `sequence`, actor seat, street, kind, integer chips and
  `PokerEvidence`. Actions cannot duplicate `eventId` in one observation,
  reuse a sequence or carry negative amounts. Unknown values stay unknown.
- Integer chip amounts are not floats. Fractional currencies require an
  explicitly versioned subunit. A reported raise amount's meaning must be
  verified by its adapter; no inferred bet mechanics are introduced.
- Known cards use ranks 2–9/T/J/Q/K/A and suits C/D/H/S, with duplicate known
  cards rejected. `null` denotes unknown; empty list denotes a known empty list.

## Verification: claims versus proof

- `PokerEvidence.INDEPENDENT` and nonblank `sourceProofRef`,
  `handIdentityProofRef` and `terminalProofRef` are **claims**, NOT proof.
  Merely creating or decoding a class named `VerifiedPokerHandRecord`
  **does not certify** any result. The record is an untrusted wire envelope.
- New `verificationPolicyId` is mandatory on JSON verified-hand records.
  The only policy currently supported for verification is
  `independent-full-record.v1`; any other policy => `UNSUPPORTED_POLICY`.
- `PokerHandVerificationGate.verify(candidate, verifier)` returns
  `MISSING_VERIFIER` without a trusted host-injected
  `PokerHandProofVerifier`. The verifier MUST authenticate the **whole**
  candidate, binding namespace, ruleset, table/session/hand, events, result,
  proof references and field values. It must reject manipulated data.
  Verification callback failure/exception => `PROOF_REJECTED`.
- Even an accepting verifier cannot override `UNSUPPORTED_SCHEMA`,
  `UNSUPPORTED_RULESET`, or `UNSUPPORTED_POLICY`.
- A non-null hero-net outcome requires `resultEvidence=INDEPENDENT` in
  addition to external full-record verification. No OCR or disappearing
  cards may certify a settlement. No external provider is implemented here.
- Neither this model nor the new proof gate replaces T042's
  `VerifiedHandCompletionGate` or grants an action/learning permission.

## Replay and import contract (no storage introduced)

`PokerCanonicalImport` is stateless and returns `NEW`, `DUPLICATE`,
`CONFLICT` or `UNIDENTIFIABLE` when relevant:

- Observation with the same stable observation key and identical decoded
  data: `DUPLICATE`; same key with different facts: `CONFLICT`.
- Event with the same stable event key and identical action + ruleset:
  `DUPLICATE`; same key with changed amount, kind or ruleset:
  `CONFLICT`. Different hand scope is a different event.
- Verified hand with same `handRecordId` and identical decoded record:
  `DUPLICATE`; reused ID with changed ruleset, settlement or proof:
  `CONFLICT`. No implicit merge of conflicting training records.
- The caller supplies the previously persisted record after a restart.
  This layer does not persist anything; a future storage/sync engine must
  implement an atomic key lookup and persist only records independently
  verified under a supported policy and ruleset.

## Compatibility and test boundaries

- Only Stage 1 files and tests are modified. Existing `VerifiedFinishedHand`,
  `PostHandLearner`, `AndroidHandLearningStore`, `VerifiedHandCompletionGate`,
  `ChatGptCloudBridge`, OCR/overlays, STOP and protected action runtime remain
  unchanged and not connected to canonical import.
- Two synthetic `PokerSourceAdapter<T>` implementations normalize different
  test inputs without connecting to any third-party poker app.
- Coverage: Python source isolation, pure JVM synthetic scenarios for 2–9
  seats and replay, Android JVM unit tests for JSON, missing fields, mixed
  rules, stable keys, tampering, versions, and unsupported policies.
