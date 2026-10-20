# Work / Android CI: single controlled build for v2.14.41

Use the entire v2.14.41 ZIP (or the patch relative to v2.14.40), not a new project.

1. Review `android/app/src/main/java/com/example/airemote/ChatGptCloudBridge.kt` and ensure `androidx.browser:browser:1.8.0` compiles with the project's Android SDK. Run JVM and Android unit tests.
2. Run `gradle --no-daemon :app:testDebugUnitTest` and `gradle --no-daemon :app:assembleDebug` within `android/`; fix real compilation or manifest errors before release.
3. Test the browser-owned Custom Tab on an Android emulator or available test device with simulated OAuth callback, timeout and network exceptions. Do not use any real user's password or 2FA codes in automated tests.
4. The candidate debug APK **must** have package `com.example.airemote.dev`, `versionCode=75`, versionName `2.14.41-in-app-oauth-hardening-test`, and the **same signer certificate** as v2.14.39/40. Reference signer SHA-256: `511567f44b5e527b8b388d0ea2a66ad30725a856ef76670c027b88c25007cd2c`. Do not publish with newly generated debug key; never expose signing secrets.
5. Confirm Android `android.permission.ACCESS_NETWORK_STATE`, `INTERNET`, no exported token provider, callback only on loopback. STOP and all operator restrictions unchanged.
6. Check OAuth callback state/PKCE, JWKS signature and `nonce`, rotating refresh-token storage, correct error classifications, no token logs or unsafe retries.
7. Ensure automatic post-login proof waits for a real `response.completed` from OpenAI. Successful compilation is NOT proof of working authentication or ChatGPT plan access.
8. Produce APK, SHA-256, Gradle logs, `aapt` package metadata, `apksigner` certificate verification, and one concise report. Until then do NOT ask the user to install again; do not ask for password, 2FA or token.

The existing v2.14.40 on the user's device is not touched by this source-only update.
