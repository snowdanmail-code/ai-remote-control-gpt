# T043 Stage 3 — FTP Sync Engine implementation / security & recovery report

**Approved base:** `de37edc06f597d5a2d2d0d5cf0d53d746d3db3a0`.
**Branch:** `feature/t043-ftp-sync` (Draft, do not merge).
**Scope:** isolated local sync components and tests. No Trainer, APK, OAuth, STOP, OCR,
game strategy, real production FTP credentials, or actual server connections.
**No SQLite schema modifications:** all four legacy and eleven T043 tables remain unchanged.

## Wire protocol: T043-FILESYNC/1

- Deterministic, bounded binary header with magic, protocol v1 and explicit
  `HPKE-BASE-X25519-HKDF-SHA256-AES256GCM-ED25519-v1`.
- Authenticated device ID, signing key ID and version; recipient device ID,
  encryption key ID and version; **separate** original Stage-2 logical source,
  recipient, message ID, stream, sequence, content type and all other
  identity fields necessary to verify original `envelope_sha256`.
- HPKE encapsulation `enc` (32 bytes), AES-256-GCM ciphertext and Ed25519
  signature are all present. Signing input domain-separates and covers
  exact header bytes + `enc` + exact ciphertext. The header is AEAD
  associated data. A changed field, ciphertext or `enc` invalidates auth.
- Sender sign key is provisioned OUT OF BAND. FTP directory listings do not
  register device keys; authorized `logicalSenderId` must be on a local
  pinned-device allowlist. Device ID and game-data namespace are distinct.
- Strict bounds: maximum 1,048,576 bytes cleartext and 1,052,672-byte
  packet, including manifest/enc/signature overhead.
- The original Stage-2 payload is never re-serialized or the internal
  `envelope_sha256` altered; receiver re-checks plaintext's raw SHA and
  full Stage-2 envelope digest.
- Crypto implementation: Bouncy Castle bcprov-jdk18on 1.85, RFC 9180
  base HPKE with X25519/HKDF-SHA256/AES-256-GCM; Ed25519 digital signature
  is independent of HPKE (HPKE base mode alone does not authenticate sender).

## Key storage

`T043AndroidKeyVault` wraps externally enrolled 32-byte private seeds
with an app-local AndroidKeyStore AES-256-GCM wrapping key. The ciphertext
lives under private `noBackupFilesDir`. Key access is scoped to a callback;
decoded bytes are zeroed at its boundary. The provisioning API never accepts
a key from FTP and does not silently rotate keys. Hardware backing is
device-dependent: `isHardwareBacked()` permits a deployment gate but cannot
promise TEE on every Android 10+ phone. This project cannot conclude that the
actual Samsung hardware backs a key without testing it. No private keys,
credentials, generated test seeds or plaintext dumps are checked in.

## FTPS file transport

- `T043FtpsTransport` uses Apache Commons Net 3.13.0 `FTPSClient("TLS",false)`.
- Explicit platform CA trust-chain validation via `TrustManagerFactory` + **TLS endpoint hostname verification** (Commons Net's validity-only default must not be used).
  Only TLS 1.2/1.3; `PBSZ 0` + **`PROT P`** mandatory for file channel.
  No plaintext downgrade, trust-all SSL, or automatic retry as unencrypted FTP.
- Real access requires an explicitly approved `T043FtpsEndpoint`;
  the default approval state is **false**. Credentials are supplied
  exclusively at call time, never placed in the repository.
- Remote location:
  `/t043/v1/inbox/{recipientDeviceId}/{first32Hex(SHA256(streamId))}/`
  with final filename
  `m-{SHA256(logicalSenderId||"|"||messageId)}-{sequenceNo:020d}.t043`.
  Device IDs are strictly ASCII-safe and no user-supplied path is concatenated
  without validation.
- Files upload to an unlisted dot-prefixed `.tmp` name; stored bytes are
  re-read for equality before rename to the final filename. The receiver
  lists ONLY final `.t043` files and parses & verifies the signed protocol.
  Conflicting final names do not overwrite an existing file.
- FTP server / TLS certificate are not configured in this branch. No live
  FTPS transfer has been performed, and no claim of compatibility with the
  actual user's server is made.

## Durable route journal / restart

`T043RouteStore` writes a private Android `AtomicFile` record for each
`(logicalSenderId,messageId)` after encryption, before uploading.
The record permanently pins the chosen device, public-key versions and
**exact prepared HPKE encrypted packet**. A retry or restart looks it up
before any encryption attempt. Any different device, key or packet for the
same immutable message is rejected; no automatic reroute or re-encryption.
A partial write is rolled back by `AtomicFile`; corrupt records fail closed.

`T043SyncCoordinator.publish` calls FTPS only via an injected transport.
On FTPS failure the pending encrypted packet stays on disk and SQLite
outbox remains non-ACKED. A successful upload moves the outbox only to
`SENT_UNCONFIRMED` (never ACK). Application receipt verification is
a separate operation.

## Durable ingress and trust boundary

`T043SyncInboxTransport` validates sender authorization, exact HPKE
header, Ed25519 signature, AEAD and original Stage-2 hashes BEFORE writing
the raw signed packet to a separate private `AtomicFile`.
The signed packet is durable before SQLite `RECEIVED`; after restart a
trusted importer re-verifies that packet. Duplicate IDs are handled by the
existing inbox key and conflict journal, not merely by FTP filenames.

**Crucial:** Stage-2 diagnostic `applyInboxAtomically()` is unchanged and
does NOT verify or import hand results. Its `APPLIED` is not proof that
a game record was saved. Verified hand imports use the **separate**,
fixed `T043VerifiedImportBridge` only and require:
- authenticated stored inbound signed wire packet,
- supported ruleset/policy/schema,
- independently checked `PokerHandVerificationGate`,
- byte-bound verification of the immutable pinned JSON,
- recomputed raw & semantic checksums.

No production verifier is registered. Null / absent verifiers fail closed.
Only isolated synthetic tests supply intentionally permissive fixtures.

Once verification succeeds, ONE SQLite transaction inserts the hand and
independently verified events (or checks equivalent existing hand), marks
inbox `APPLIED`, advances its gap-free cursor and inserts a permanent,
**Ed25519-signed** `t043.receipt.v1` message in the same outbox tables.
A remote verified hand **never** enqueues another verified-hand message, so
there is no Player ↔ Trainer hand echo. A failure at any point of receipt
creation rolls back hand/events/APPLIED/receipt/cursor.

Receipt statements distinguish `VERIFIED_IMPORTED`, `DUPLICATE_COMMITTED`,
`RECEIVED_DURABLE`, `DIAGNOSTIC_APPLIED`. Only the first two can ACK a
verified-hand outbox record. The receipt manager checks authenticated signing
key and version, local intended device, original message ID/stream/sequence,
original `envelope_sha256`, confirmed hand ID, *and the sender device pinned
in the route journal*. ACK of the original, inbound receipt `APPLIED` and
contiguous receipt cursor progress occur in ONE SQLite transaction. A
repeated valid receipt is idempotent; a different receipt cannot mutate an
already terminal ACK.

## Test suite

- Existing 456 Python tests, 88 canonical JVM tests, 21 fingerprint JVM tests,
  and existing Android unit/regression tests stay enabled.
- `T043Stage3RobolectricTest` has 37 named T3-01..T3-37 tests of:
  real RFC9180/Ed25519 roundtrip, signature/enc/header/ciphertext/key tampering,
  revoked or unapproved devices, durable dedupe, gap ordering, no FTP ACK,
  offline recovery, fixed route/byte preservation, independent source names,
  absence of a trusted production verifier, synthetic verified import+signed
  receipt, forced SQLite crash rollback, no echo, and repeated receipt ACK.
- Fault injection for transport is through a fully **isolated in-memory
  adversarial transport**, not a network FTP/FTPS server.
- `T043FtpsSecurityJvmTest` uses an **isolated loopback Apache FTPServer**
  with a freshly generated ephemeral test certificate. T3-35a requires
  rejection of its untrusted self-signed certificate; T3-35b requires
  rejection of a plaintext FTP endpoint without downgrade.
  Neither test uses the actual FTP server or production credentials.
- Hardware-backed AndroidKeyStore provisioning remains subject to actual
  target-device verification before deploying production secrets.

## Outstanding acceptance gates

Real server endpoint/certificate and network login, target-device
AndroidKeyStore support/hardware backing, and independent proof service
remain unconfigured/not executed. A separate loopback FTPS negative-handshake
fixture has been added; its CI result must be checked before approval. These are
explicit requirements before deployment, not implicit production readiness.
No PR may be merged or app package built without separate authorization.
