# AI Remote Control GPT — v2.14.26 (Real Frame Geometry Diagnostics)

**Source-only, not an APK. Not proof of an autonomous AI player in the original 1win app.** Previous v2.14.25 is unchanged.

## What is actually implemented

- `RealFrameRegionDiagnostics.kt`: read-only routing of positioned ML Kit OCR lines through explicitly measured areas of an **authentic captured Android JPEG**: seat action labels, stacks/contributions, turn tags, bank and timer.
- Strict `RealFrameCalibration`: explicitly named Android package, captured JPEG dimensions, normalised regions, 2–10 distinct seat action regions, no same-role region collisions; no default geometry or fabricated 1win package.
- Fail-closed diagnostic checks: STOP, epoch/window changes, frame replay, age, package mismatch, dimensions mismatch, conflicting OCR lines, ambiguous numeric units. No inference of table/hand ID or opponent identity.
- Connected to existing background OCR under the atomic FrameStore publication guard. Findings are **not** `ConfirmedOpponentAction`, not a verified `SeatStateSnapshot`, not independent numeric facts, and never permission for `dispatchGesture`.
- Android operator panel now imports a bounded JSON calibration via Android Storage Access Framework, shows redacted observation status and can clear the profile. Automatic re-application after frame resets restores **geometry only**, not any previous OCR evidence. STOP clears in-memory calibration.
- `tools/prepare_real_frame_calibration.py` can convert **manually measured** pixel rectangles on an operator-provided authentic frame into importable JSON. It never uploads, analyses, guesses layout, exports, or stores images. Example regions are only syntax examples and are **NOT** the real 1win layout.
- Updated versionCode=60 and manual CI artifact naming.

## What is NOT implemented or proven

- We do not yet have **authentic, operator-approved, annotated original 1win frames** in this workspace to generate calibrated coordinates or measure sensitivity/false-positive rates.
- OCR labels alone are not an independent source of seat contributions, stacks, turn ownership, table/hand identity, or evidence that an opponent acted; the v2.14.25 transition checker remains **diagnostic only**.
- No live Android device run, on-device JSON picker validation, Android Gradle/JUnit test run, installed APK, GPT strategy connection, or authorized automated 1win gestures.
- There is no gameplay simulator or substitute poker app. Synthetic unit-test inputs test defensive code paths only.

## Checks executed

- Source ZIP v2.14.25 SHA-256 verified against saved checksum before any changes.
- `bash tools/run_jvm_tests.sh` passed, including **35 new real-frame Kotlin checks** and previously existing poker-math, opponent/history, STOP and OCR suites.
- `python -m unittest discover -s tools -p 'test_*.py' -q`: **207 / 207 passed**, including 10 new calibration-tool tests.
- Three Android JUnit scenarios added, *not yet run* (Android SDK/Gradle unavailable locally).

## Acceptance criteria before any real use

1. Supply redacted genuine Android frames with the actual frame/JPEG pixel size and observed package identifier, from an expressly permitted play-money session. No login/payment screens.
2. Manually mark actual seat areas; use calibration script to produce Android-importable JSON and verify it on device.
3. Annotate a set of independent sequences spanning turn changes, pots, stacks, menus and animations; measure false positives in a blind holdout set.
4. Independently verify table and hand identities and action semantics, then separately review any path towards the learning gate or live-control gate. Never infer truth from text/geometry alone.
5. Build Android debug APK in an appropriate Android SDK / Gradle environment and check permissions, lifecycle, STOP, UI and export before extended testing.

The local incident recorder from v2.14.21 remains in place. No covert uploads; reports are exported manually by the operator.

## Operator-controlled calibration preparation

**Do not run this with example coordinates as if they describe 1win.** After collecting a permissible original, redacted Android screenshot and independently measuring the bounding rectangles in its pixels, make a JSON with `zones` entries (`role`, optional `seatIndex`, `boundsPixels` = `[x1,y1,x2,y2]`). Use observed package identity and the actual app-captured JPEG dimensions (from the phone's diagnostic label):

```bash
python tools/prepare_real_frame_calibration.py \
  --image /path/to/authentic-redacted-frame.png \
  --regions /path/to/operator-measured-zones.json \
  --package com.example.actualObservedPackage \
  --layout-key measured-portrait-1 \
  --capture-width 540 --capture-height 1170 \
  --output /path/to/real-frame-layout.json
```

The example package above is **placeholder syntax**, not a statement about 1win. Capture dimensions above are also examples: supply exact values from your current JPEG, and the image aspect ratio must match. Then use the Android `ЗАГРУЗИТЬ КАЛИБРОВКУ РЕАЛЬНОГО КАДРА (JSON)` button. The output never contains or transmits an image. After `STOP`, reload the operator-approved profile.
