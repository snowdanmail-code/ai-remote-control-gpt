# AI Remote Control GPT v2.14.24 — Seat Visual Diagnostics

**Source-only release.** Previous v2.14.23 is preserved. This is not an APK and not a working autonomous 1win poker player.

## Changes

1. Added `SeatVisualDiagnostics.kt`: configurable normalized seat rectangles; read-only recognition of **exact** OCR action words appearing wholly inside a seat region.
2. Bound diagnostics to OCR ticket (capture epoch, frame sequence, capture timestamp, foreground package, window generation) and exact frame resolution. Duplicate/out-of-order, old, cross-window or cross-epoch observations reset all calibration and evidence.
3. Two independent frames can confirm only a **repeated on-screen label**, not that an opponent took a real action. Ambiguous duplicate labels, text outside calibrated regions, player chat and hero bottom buttons cannot become accepted opponent history.
4. Integrated the monitor with the atomic OCR publication in `OnDeviceAutomaticOcr`, `FrameStore` and the diagnostic button in `MainActivity`. STOP and loss of foreground clear seat diagnostics and calibration.
5. Added 44 independent Kotlin checks, 7 Python source integration checks, and 4 Android JUnit tests **for future execution**. The full existing test suite remains intact.
6. VersionCode 58 / versionName `2.14.24-seat-visual-diagnostics`; manually triggered CI artifact filenames updated, but CI/APK was **not run**.

## Important limitations

- **No default live 1win calibration**: the real application UI was not available as annotated screenshots or live frames in this environment. Calibration must be supplied from verified, labelled captures with device resolution and source package.
- OCR seat labels do **not** prove real game actions, table/hand IDs or opponent identity. This module **never calls** `LiveOpponentAwareness.observe`, `dispatchGesture`, SQLite or an authorization gate.
- Real opponent recognition needs an independent positive action-transition detector and authenticated table/hand and opponent identities. Guessing stable identity from a seat index or OCR name would pollute the persisted database.
- Android SDK/Gradle and a connected Android device were unavailable. No APK, no Android Gradle/JUnit run, no real 1win test, and no measurements of model accuracy were made.
- Platform permission for automated gameplay remains unverified. Autonomous taps remain blocked.

## Following step

Calibrate positions from user-supplied **redacted** original 1win frames/videos with labels for a known hand, note variation during animation and screen size, assess false-positive/false-negative rates, build independently confirmed action-transition evidence, and only then attach it to the existing post-hand learning gate.

For QA use: `bash tools/run_jvm_tests.sh` (needs Kotlin/JVM); `python -m unittest discover -s tools -p 'test_*.py' -q` (needs Python). Android CI is intentionally manual.
