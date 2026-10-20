# Work/CI build handoff — v2.14.39

Use attached v2.14.39 sources, not v2.14.38 APK. Preserve `com.example.airemote.dev`, versionCode 73 and the original signer certificate fingerprint.

1. Review and compile Kotlin and Android 16 project with installed Android SDK/JDK 17/Gradle.
2. Execute Python and Kotlin tests and Android `:app:testDebugUnitTest`. Fix errors in a branch without modifying previous APKs.
3. Verify v2.14.39 final APK package, version and signature against `tools/reference_prior_apk_v2.14.36.json` using `tools/verify_android_upgrade.py`. If previous signing keystore is missing, block the release: do not sign using an unrelated debug key and do not request removal of installed app.
4. Confirm only the existing minimal primary UI plus STOP. No new buttons. Do not embed secrets or 2FA data.
5. Validate that JWKS DNS/network errors remain network errors, and that OIDC validation remains strict for issuer, signature, audience, time, nonce and subject.
6. For a real user check, do **one** sign-in attempt after release via Chrome and 2FA. Only a saved session followed by an actual model reply proves success; browser callback alone is insufficient.
7. Supply verified APK and SHA-256 only if actual Gradle build and signing verification succeed. Explicitly report if unavailable.
