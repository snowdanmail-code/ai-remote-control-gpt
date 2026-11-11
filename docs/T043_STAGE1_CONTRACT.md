# T043, Stage 1 — Canonical Texas Hold'em v1

Scope: passive, platform-independent data contracts only. No live app adapters,
provider authentication, real-event generation, networking, persistent schema
migration, Trainer, action dispatch, or APK are supplied in Stage 1.

## Versioning and encoding

- Root `recordType`: `observation` or `verifiedHand`.
- `schemaMajor=1`, `schemaMinor=0`. Readers accept `major=1` and nonnegative
  future minor revisions; they discard unknown JSON fields and parse unknown
  enum strings as `UNKNOWN`. They reject unknown major revisions, invalid
  card names, duplicate cards/seats/verified identities, and invalid table sizes.
- JSON encoding lives in `CanonicalPokerJson.kt` using Android `org.json`.
  Pure Kotlin contract (`CanonicalPokerModel.kt`) does not depend on Android,
  screen coordinates, an OCR implementation, or other project components.
- Serialization is semantic (round-trip objects); key order is not a wire
  guarantee. Future minor producers MUST NOT redefine old field semantics.
- Currency amounts are nonnegative integer chip units. No float conversion;
  for fractional chip currencies a later version must introduce a denomination
  or integer subunit explicitly rather than silently rounding.

## Observation

`CanonicalPokerObservation` contains:

- `source.namespace`, `source.tableId`, `source.sessionId`, `source.handId`;
  `observationId`, optional capture timestamp; 2–9 `tableCapacity`.
- `seats` (observed seated participants; incomplete tables remain partial),
  `seatIndex` in `[0, tableCapacity)`, explicit `seatingComplete`, `dealtIn` and `activeInHand` tri-state,
  optional `participantId`, nickname, identity evidence, integer stack,
  hole cards. Nickname cannot serve as profile key.
- Optional `buttonSeat`, `heroSeat`, current `street`, known board cards,
  optional pot, chronological `actions` (`sequence`, actor seat, street, kind,
  reported amount, evidence), observation evidence.
- `knownActiveCount()` returns null if the seat manifest is incomplete or any player has unknown active
  state. `positions()` is empty if button or dealt-in membership is unknown.
  Otherwise, only with `seatingComplete=true`, it orders dealt-in seats clockwise from button and assigns
  heads-up BUTTON_SMALL_BLIND/BB, and 3–9-handed named positions; folded
  players retain their start-of-hand position. If the provider's seat indexing
  is not clockwise, its adapter must normalize seat numbering first.
- Known card values use `rank` in 2–9/T/J/Q/K/A and `suit` in C/D/H/S.
  `null` means unobserved; an empty collection means a known empty collection.

## Source identity and confidence

- `source.namespace` MUST identify a distinct upstream application/provider
  and instance/tenant; collision handling belongs to the registering host.
- `stableParticipantKey(seatIndex)` only exists with verified participant ID,
  source namespace and stable session ID. It is length-prefixed and session
  scoped. Same nickname on unrelated apps/sessions NEVER creates shared IDs.
  This is a structural rule, not cryptographic authentication.
- Evidence: `UNKNOWN`, `OCR_ONLY`, `CORROBORATED`, `SOURCE_VERIFIED`,
  `INDEPENDENT`. Enums never promote unrecognized values to trusted.
  OCR must not be passed as independently verified settlement evidence.

## Verified hand

`VerifiedPokerHandRecord` contains a terminal observation, nonblank external
source/hand-ID/terminal proof references, independent evidence grades for each,
and an optional hero-net outcome. A non-null net result requires independent
result evidence; OCR or inferred payout cannot be labeled verified. Hand
termination can happen on any street.
Only a caller with external independent verification may populate proof fields.
The data class itself cannot validate external signatures and **must not** be
used as a substitute for `VerifiedHandCompletionGate` in T042.

## Adapter interface

`PokerSourceAdapter<T>.normalize(event)` returns `CanonicalPokerObservation`.
`PokerSourceAdapterBoundary.normalize` rejects cross-namespace results. No
coordinates, pixels, colors, application-specific strings or real provider
code exist in the common contract. Two pure synthetic fixtures (Alpha map
and Beta typed event) demonstrate adapter independence.

## Compatibility with T042

Existing `VerifiedFinishedHand`, `PostHandLearner`, `AndroidHandLearningStore`,
`VerifiedHandCompletionGate`, `ChatGptCloudBridge`, OCR, STOP, UI and action
runtime remain untouched. No automatic bridge connects canonical records to
live proof/decision/learning/storage; any migration or bridge requires a later
review. The test runner adds one isolated Kotlin scenario only.

## Required verification

`tools/test_canonical_poker_contract.py` (static contract guards),
`tools/CanonicalPokerScenarios.kt` (two fixture adapters, every table size
2–9, positions, identity isolation), and
`android/app/src/test/java/com/example/airemote/CanonicalPokerModelTest.kt`
(JSON round trip, fields/versions, unknowns, verification guards).
