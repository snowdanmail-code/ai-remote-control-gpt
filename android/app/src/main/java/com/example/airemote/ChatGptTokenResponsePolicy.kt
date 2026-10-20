package com.example.airemote

object ChatGptTokenResponsePolicy {
    fun expiresAt(nowMs: Long, seconds: Long, tokenType: String): Long {
        require(nowMs >= 0 && tokenType.equals("Bearer", true))
        require(seconds > 0 && seconds <= (Long.MAX_VALUE - nowMs) / 1000L)
        return nowMs + seconds * 1000L
    }
    fun requiresReauthorization(pendingRefresh: Boolean): Boolean = pendingRefresh
    fun sameIdentity(client: String, sub: String, savedClient: String, savedSub: String): Boolean =
        client == savedClient && sub.isNotBlank() && ChatGptAuthProtocol.secureEquals(savedSub, sub)
    fun unusableRefresh(code: String?): Boolean = code in setOf("invalid_grant", "invalid_refresh_token", "token_expired", "refresh_token_expired", "refresh_token_invalidated", "refresh_token_reused")
}
