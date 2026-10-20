package com.example.airemote

import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** OpenAI Sign in with ChatGPT: public-client, dynamic OSS registration, no embedded client secret. */
object ChatGptAuthProtocol {
    const val ISSUER = "https://auth.openai.com"
    const val AUTHORIZE = "$ISSUER/api/accounts/authorize"
    const val TOKEN = "$ISSUER/api/accounts/oauth/token"
    const val JWKS = "$ISSUER/.well-known/jwks.json"
    const val RESOURCE = "https://api.openai.com/v1"
    const val CALLBACK_PATH = "/auth/callback"
    const val SCOPE = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"
    const val INITIAL_CLIENT = "dynamic_agent_client"
    const val APP_NAME = "AI Remote Control GPT"

    fun randomUrlSafe(bytes: Int = 32): String {
        require(bytes in 16..64)
        val raw = ByteArray(bytes)
        SecureRandom().nextBytes(raw)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
    }

    fun challenge(verifier: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    fun secureEquals(expected: String, actual: String): Boolean =
        MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), actual.toByteArray(Charsets.UTF_8))

    fun formValue(s: String): String = URLEncoder.encode(s, "UTF-8")

    fun form(params: List<Pair<String, String>>): String =
        params.joinToString("&") { (k, v) -> "${formValue(k)}=${formValue(v)}" }

    fun authorizationUrl(hostId: String, redirectUri: String, state: String, nonce: String,
                         verifier: String, existingClientId: String? = null, requestPlanConsent: Boolean = false): String {
        require(hostId.startsWith("urn:uuid:") || hostId.startsWith("urn:ietf:params:oauth:jwk-thumbprint:"))
        require(isLoopbackCallback(redirectUri))
        require(state.length >= 32 && nonce.length >= 32)
        require(Regex("^[A-Za-z0-9._~-]{43,128}$").matches(verifier)) { "Invalid PKCE verifier" }
        val client = existingClientId ?: INITIAL_CLIENT
        require(client.isNotBlank() && (existingClientId == null || client != INITIAL_CLIENT))
        val params = mutableListOf(
            "client_id" to client, "response_type" to "code", "redirect_uri" to redirectUri,
            "scope" to SCOPE, "resource" to RESOURCE,
            "ext_agent_host_id" to hostId, "state" to state, "nonce" to nonce,
            "code_challenge_method" to "S256", "code_challenge" to challenge(verifier)
        )
        if (existingClientId == null) params += "agent_name_hint" to APP_NAME
        if (requestPlanConsent) params += "prompt" to "consent"
        return "$AUTHORIZE?${form(params)}"
    }

    fun isLoopbackCallback(value: String): Boolean = try {
        val u = URI(value)
        u.scheme == "http" && u.host == "127.0.0.1" && u.port in 1024..65535 &&
            u.rawPath == CALLBACK_PATH && u.rawUserInfo == null && u.rawQuery == null && u.rawFragment == null
    } catch (_: Exception) { false }

    data class Callback(val code: String, val issuedClientId: String)

    /** Fail closed on cancelled, replayed, malformed or cross-session authorization callbacks. */
    fun validateCallback(rawPath: String, redirectUri: String, expectedState: String,
                         pendingClientId: String?): Callback {
        require(isLoopbackCallback(redirectUri))
        val u = URI("http://127.0.0.1$rawPath")
        require(u.rawPath == CALLBACK_PATH && u.rawFragment == null)
        val q = linkedMapOf<String, String>()
        (u.rawQuery ?: "").split('&').filter { it.isNotEmpty() }.forEach { p ->
            val raw = p.split('=', limit = 2)
            val key = java.net.URLDecoder.decode(raw[0], "UTF-8")
            val value = java.net.URLDecoder.decode(raw.getOrElse(1) { "" }, "UTF-8")
            require(!q.containsKey(key)) { "Duplicate OAuth parameter" }
            q[key] = value
        }
        require(secureEquals(expectedState, q["state"] ?: "")) { "OAuth state mismatch" }
        require(!q.containsKey("error")) { "OAuth authorization denied" }
        val code = requireNotNull(q["code"]) { "Missing authorization code" }
        require(code.length in 8..4096 && !code.contains('\r') && !code.contains('\n'))
        val received = q["client_id"]
        val clientId = if (pendingClientId == null) {
            require(!received.isNullOrBlank() && received != INITIAL_CLIENT) { "No issued OAuth client ID" }
            received
        } else {
            require(received == null || secureEquals(pendingClientId, received)) { "Client ID changed" }
            pendingClientId
        }
        require(clientId.length < 512)
        return Callback(code, clientId)
    }

    fun scopeAllowsInference(scope: String): Boolean =
        scope.split(Regex("\\s+")).toSet().containsAll(setOf("resource.invoke", "chatgpt.tokens.use.direct"))

    /** Compatible with 2026 SIWC direct Responses preview, intentionally omits unsupported fields. */
    fun inferenceInput(history: List<String>): List<String> {
        require(history.isNotEmpty() && history.size <= 8)
        require(history.all { it.length in 1..4000 })
        return history.toList()
    }
}
