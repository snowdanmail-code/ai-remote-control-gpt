# AI Remote Control GPT v2.14.39 — OIDC Identity and Intermittent DNS Fix

**Source-only release candidate. Not a verified Android APK or working sign-in.**

## Findings from 1win/ChatGPT Android screenshots (2026-10-18)

- v2.14.38 pre-login app-native network test returned `AUTH DNS_OK HTTPS_REACHABLE HTTP_200` and `API DNS_OK HTTPS_REACHABLE HTTP_401`; HTTP 401 on unauthenticated `/v1/models` means the API endpoint was reached, **not** that sign-in was successful.
- An attempt reported `AUTH_TOKEN_DNS_FAILURE`, another `AUTH_IDENTITY_BAD_IDENTITY`.
- **Confirmed bug in v2.14.38:** The ID-token validation was wrapped in `catch (_: Exception) { BAD_IDENTITY }`, which hid `UnknownHostException`, TLS, HTTP errors and JWKS JSON errors as a false ID-token claim failure.
- **Confirmed interoperability issue:** The verifier rejected ID tokens lacking the optional JWT `typ` header. This header is not a mandatory OpenID Connect security check; RS256 signature is still required.
- Intermittent Android DNS is **not proven to be a Starlink-wide outage**, since the preflight succeeded. It can fluctuate between the preflight and token exchange. The cause of the actual connection failure has not been proven from screenshots.

## Changes

1. Removed catch-all around `verifyIdToken`; errors in fetching `https://auth.openai.com/.well-known/jwks.json` are now classified as network/HTTP errors at the `AUTH_IDENTITY` stage.
2. Added distinct non-secret `AUTH_IDENTITY_*` codes for malformed JWT, unsupported algorithm, unusable JWKS, missing key, invalid RS256 signature, wrong issuer, wrong audience or authorized party, invalid validity period, nonce mismatch and missing subject.
3. Removed the unnecessary `typ` header check, retaining strict RS256, JWK `kid`, cryptographic signature, issuer, client ID, temporal, nonce and subject checks.
4. Added a **maximum two DNS lookup attempts before opening any auth-server POST**. Crucially, token exchange, refresh-token grants and their HTTP POSTs are never automatically retried after an uncertain send. Fresh PKCE/OAuth code is required for a new sign-in attempt.
5. Extracted independently tested `ChatGptIdentityPolicy` for claim validation; added pure Kotlin tests for it and DNS admission.
6. Corrected an empty YAML `env:` mapping in `.github/workflows/android.yml`. CI still requires the original signing keystore; it must not publish an incompatible debug APK.
7. Preserved existing simplified UI, local OCR, strategy, STOP and gesture guards. **No extra diagnostics buttons.**

## Verification and limits

- Python source and release-contract tests: see `QA_v2.14.39.json`.
- Kotlin/JVM pure scenarios: see `QA_v2.14.39.json`.
- Android-dependent cloud code compiled with local stubs only, **not** Android SDK, Gradle or a live Android device.
- OpenAI's production discovery document lists issuer `https://auth.openai.com`, RS256 signing and the token and JWKS endpoints used by the project: https://auth.openai.com/.well-known/openid-configuration.
- No account tokens, 2FA codes, personal account secrets or video recordings included.
- No real authentication completed during tests. Cloud inference and autonomous taps are NOT verified.

## Upgrade rules

- Expected debug application ID: `com.example.airemote.dev`; new versionCode: `73`; previous released v2.14.36 versionCode: `70`.
- Previously installed v2.14.35/v2.14.36 APK signing fingerprint: `511567f44b5e527b8b388d0ea2a66ad30725a856ef76670c027b88c25007cd2c`.
- The previous *private signing keystore* is required to upgrade in-place. A certificate extracted from an APK cannot be used to reproduce its signature.
- Do not ask the operator to uninstall the app or re-enter 2FA until a signed, compatible APK passes actual CI checks. Build gate: `tools/verify_android_upgrade.py`.
