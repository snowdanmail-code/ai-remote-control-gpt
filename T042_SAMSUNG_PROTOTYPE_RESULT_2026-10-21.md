# T042 — Samsung-first experimental device test

Status: READY_FOR_DEVICE_TEST (experimental prototype, not final v2.14.42).

## Actual result
- Source: branch t042-checkpoint, commit b67c9bd852fd7881dc55c5154d9bd0fa451b091e.
- CI: https://github.com/snowdanmail-code/ai-remote-control-gpt/actions/runs/39514968093
- Job 118944822440: SUCCESS. gradle --no-daemon :app:assembleDebug: BUILD SUCCESSFUL in 3m 5s.
- Package com.example.airemote.dev, versionCode 76, versionName 2.14.41-t042-samsung-prototype-test.
- APK: AI-Remote-Control-GPT-T042-Samsung-Prototype.apk, 53,014,945 bytes.
- APK SHA-256: 677d6996ebc0ce9edb37c222e123610b7f7b419551fca7603d5ead9ff38e42da.
- Original private key restored from protected environment; no new signing key generated.
- aapt, apksigner and update gate PASS; signer SHA-256 511567f44b5e527b8b388d0ea2a66ad30725a856ef76670c027b88c25007cd2c matches baseline v2.14.41/code75.
- Downloaded artifact digest matches Actions digest 23661f75444d127caffb1c3ea0c1cf390fa3b72ecd103435a7c0923824088c06. Extracted APK SHA-256 matches CI. APK ZIP CRC, manifest and DEX PASS.
- Runner keystore cleanup PASS. No signing key/password included in repository or artifacts.

## Scope and evidence limits
The user explicitly changed development order to Samsung-first prototype testing. The protected 16-test route remains in CI; this manual run selected samsung_prototype=true, promote_t042=false. Production application code was not changed for this run. Only CI staging changes version metadata in its ephemeral checkout. Original source versions and previous artifacts are preserved.

Current run: Python/JVM/Android unit/instrumentation tests SKIPPED intentionally; no new test PASS claimed. Previous run 39513115952 passed 321 Python tests, JVM scenarios, Gradle unit tests, instrumentation compilation, signed target/test APK inspection and KVM check. Its emulator failed before boot due to insufficient disk (5,409.59 MB available vs 7,372.80 MB required); it was cancelled. All 16 Android runtime tests remain NOT_RUN. A 3072M emulator data partition is prepared for a future explicit test run; it was not tested in this prototype run.

Samsung installation, ChatGPT token exchange, real model response, encrypted session persistence across restart, MediaProjection/OCR, accessibility touches and STOP runtime behavior are NOT_TESTED. Compilation and signature verification do not establish any of those behaviors. Root cause of the Samsung DNS/identity/session failures remains unconfirmed until device testing.

## Device procedure
1. Keep installed v2.14.41 and its data. Open the APK and choose Update. Do not uninstall or clear storage. If Android reports a conflict, stop and record the exact installer message.
2. Open app with STOP engaged. Sign in through the official browser flow. Never share passwords, codes, cookies or tokens.
3. Wait for app status after callback, then check cloud connectivity. Callback landing-page text alone is not success: require actual model response.
4. Restart the app and repeat connectivity check to test session persistence.
5. Check STOP before enabling capture or accessibility. Test capture/OCR in permitted virtual-chip environment. Enable touches only after confirmed screen/session state and authorization under target app rules. No real-money play.
6. On error, keep STOP engaged and record the displayed stage/error code; diagnostic logs must exclude all authorization secrets and personal server responses.

VersionCode76 is now used by a distributed prototype. Any subsequent final in-place update must use versionCode77 or higher. Final v2.14.42 was NOT released.
