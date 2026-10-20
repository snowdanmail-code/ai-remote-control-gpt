# AI Remote Control GPT v2.14.25 — Seat Action Transition Diagnostics

**Source-only release. Not an APK, not a verified autonomous 1win player.** Previous release v2.14.24 remains unchanged.

## Implemented

- `SeatActionTransitionDiagnostics.kt`: *pure, read-only* plausibility analysis of an OCR action cue against **independently supplied** seat contributions, player stacks, active seats, turn ownership, pot, and amount to call.
- Requires three strictly ordered snapshots (before, after, settled), same verified capture epoch, table/hand context, foreground package, window generation and street; requires two agreeing, stable post-action measurements.
- Supports diagnostic plausibility for Fold, Check, Call, Bet, Raise and All-in, each with action-specific chip/turn constraints. Rejects contradictory numbers, concurrent seat changes, animation, stale/replayed frames, unknown independent identity and STOP.
- `PLAUSIBLE_DIAGNOSTIC` never creates `ConfirmedOpponentAction`, never persists SQLite records and never grants or executes Android gestures. It is NOT proof of actual player activity.
- Added 34 Kotlin/JVM scenario assertions, four future Android JUnit tests and four Python source-contract checks. Updated Android version metadata to versionCode 59 / versionName 2.14.25-seat-transition-diagnostics and manual GitHub CI filenames.
- Updated export-report version labels; no background data transmission.

## Validations actually run

- Kotlin/JVM suite: **passed**, including 839 poker-math checks, 53 opponent-awareness checks, 44 seat visual checks, 34 new seat-transition checks and prior STOP/hand-learning checks. Command: `bash tools/run_jvm_tests.sh`.
- Python: **197/197 passed**. Command: `python -m unittest discover -s tools -p 'test_*.py' -q`.
- Android Gradle/JUnit, APK install and 1win live tests **not run**. No Android SDK/Gradle available in the execution environment.

## Unresolved blocker before live use

No reliable producer yet exists for independently verified table/hand IDs, opponent seat identity, turn ownership, player stacks and contribution deltas from actual 1win frames. The new evaluator has **no automatic live pipeline**; tests use synthetic snapshots. Do not assert `independentContextVerified` / `independentStateVerified` based solely on the OCR action label. No calibration coordinates are guessed. Until independent data and annotated real frames are validated, opponent history and automated actions remain blocked.

## Next development

1. Use user-approved, redacted authentic frames/recordings to calibrate 1win player areas and annotate state transitions, including animation frames, blind positions, pot and card changes.
2. Implement an independent source of state observations with confidence/uncertainty and test against labeled examples. Measure false positives, especially same-label overlays, delayed pot/stack animations, and simultaneous opponent actions.
3. Only after evidence meets verified identity/terminal requirements should a separately reviewed adapter propose candidate events to `LiveOpponentAwareness` and then `VerifiedHandCompletionGate`; never write to SQLite on a diagnostic result alone.
4. Independently verify platform authorization for automated use. Continue Android build and on-device tests after the source modules pass.
