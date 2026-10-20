package com.example.airemote

/** One login or inference at a time; invalidation prevents late callbacks restoring a session. */
class ChatGptSessionGate {
    private var epoch = 0
    private var signingIn = false
    private var requesting = false
    @Synchronized fun tryBeginSignIn(): Boolean {
        if (signingIn || requesting) return false
        epoch++ // invalidate diagnostics/revocation callbacks from a previous sign-out
        signingIn = true
        return true
    }
    @Synchronized fun tryBeginRequest(): Boolean {
        if (signingIn || requesting) return false
        requesting = true
        return true
    }
    @Synchronized fun finishSignIn() { signingIn = false }
    @Synchronized fun finishRequest() { requesting = false }
    @Synchronized fun invalidate() { epoch++ }
    @Synchronized fun generation(): Int = epoch
    @Synchronized fun isSigningIn(): Boolean = signingIn
    @Synchronized fun isBusy(): Boolean = signingIn || requesting
}
