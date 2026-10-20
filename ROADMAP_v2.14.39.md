# Remaining before claiming ChatGPT integration works

1. Build release candidate in Android SDK/Gradle and check Kotlin/JUnit and signing compatibility.
2. Independently test full OIDC flow on test credentials under authorized conditions. Do not capture tokens in logs or share them in chat.
3. Check whether DNS failure arises in app-native resolution, TLS connection, or a resolver/proxy transition without replaying authorization codes.
4. Verify the first completed model response, user-visible only when successful.
5. Separately validate independent game-state recognition and permitted actions in original app; cloud authorization never licenses game taps.
