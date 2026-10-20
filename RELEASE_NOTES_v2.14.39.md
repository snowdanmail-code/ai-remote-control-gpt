# v2.14.39 — OAuth identity/JWKS + intermittent DNS

**Input:** screenshot evidence from installed v2.14.38, `AUTH_IDENTITY_BAD_IDENTITY` and `AUTH_TOKEN_DNS_FAILURE`, with passing unauthenticated network probes.

**Fixes:** JWKS transport errors classified correctly, detailed signed-ID-token diagnostics, no unnecessary JWT `typ` requirement, strict OIDC claim-policy with scenarios, limited DNS retries only *before* opening a token/revoke/refresh HTTPS POST, GitHub Actions YAML syntax cleanup.

**No UI additions**, no password/2FA collection, no credentials included. Real network/token exchange and in-app model reply not verified on device. No APK built.

Use `README_v2.14.39.md` and `WORK_BUILD_GATE_v2.14.39.md` for verification and build release gate.
