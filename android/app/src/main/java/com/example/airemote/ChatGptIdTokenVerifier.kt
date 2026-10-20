package com.example.airemote
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.math.BigInteger

/** Shared production verifier. Network-provider failures must retain their transport type. */
object ChatGptIdTokenVerifier {
    class Rejection(val problem: ChatGptCloudProblem) : IllegalArgumentException()
    fun verify(jwt: String, expectedClient: String, nonce: String, keys: JSONArray,
               now: Long = System.currentTimeMillis() / 1000): JSONObject =
        verifyWithKeys(jwt, expectedClient, nonce, now) { keys }
    fun verifyWithKeys(jwt: String, expectedClient: String, nonce: String, now: Long = System.currentTimeMillis() / 1000, keysProvider: () -> JSONArray): JSONObject {
        val parts = jwt.split('.')
        if (parts.size != 3 || parts.any { it.isBlank() || it.length > 32768 })
            throw Rejection(ChatGptCloudProblem.MALFORMED_ID_TOKEN)
        fun decode(value: String): ByteArray = try {
            Base64.getUrlDecoder().decode(value)
        } catch (_: IllegalArgumentException) {
            throw Rejection(ChatGptCloudProblem.MALFORMED_ID_TOKEN)
        }
        val header = try { JSONObject(String(decode(parts[0]), Charsets.UTF_8)) }
            catch (_: org.json.JSONException) { throw Rejection(ChatGptCloudProblem.MALFORMED_ID_TOKEN) }
        val claims = try { JSONObject(String(decode(parts[1]), Charsets.UTF_8)) }
            catch (_: org.json.JSONException) { throw Rejection(ChatGptCloudProblem.MALFORMED_ID_TOKEN) }
        // OIDC id_token JWT type header is OPTIONAL. Do not reject a valid signed
        // ID token merely because it omits 'typ'. RS256 remains mandatory.
        if (header.optString("alg") != "RS256" || header.has("crit"))
            throw Rejection(ChatGptCloudProblem.UNSUPPORTED_ID_ALGORITHM)
        val kid = header.optString("kid")
        if (kid.isBlank()) throw Rejection(ChatGptCloudProblem.MISSING_SIGNING_KEY)

        // Network operation must stay outside broad signature/claim catches.
        // UnknownHostException, TLS errors and CloudHttpError are preserved.
        val keys = try { keysProvider() }
            catch (_: org.json.JSONException) { throw Rejection(ChatGptCloudProblem.INVALID_JWKS_RESPONSE) }
        var key: JSONObject? = null
        for (i in 0 until keys.length()) {
            val j = keys.optJSONObject(i) ?: continue
            if (j.optString("kid") == kid && j.optString("kty") == "RSA" &&
                j.optString("use", "sig") == "sig" && j.optString("alg", "RS256") == "RS256") {
                if (key != null) throw Rejection(ChatGptCloudProblem.INVALID_JWKS_RESPONSE)
                key = j
            }
        }
        if (key == null) throw Rejection(ChatGptCloudProblem.MISSING_SIGNING_KEY)
        val pub = try {
            KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(
                BigInteger(1, decode(key.getString("n"))), BigInteger(1, decode(key.getString("e")))
            ))
        } catch (_: java.security.GeneralSecurityException) {
            throw Rejection(ChatGptCloudProblem.INVALID_JWKS_RESPONSE)
        } catch (_: org.json.JSONException) {
            throw Rejection(ChatGptCloudProblem.INVALID_JWKS_RESPONSE)
        }
        val verified = try {
            val sig = Signature.getInstance("SHA256withRSA")
            sig.initVerify(pub)
            sig.update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII))
            sig.verify(decode(parts[2]))
        } catch (_: java.security.GeneralSecurityException) {
            false
        }
        requireIdentity(verified, ChatGptCloudProblem.INVALID_SIGNATURE)
        val audiences = when (val aud = claims.opt("aud")) {
            is String -> listOf(aud)
            is JSONArray -> (0 until aud.length()).mapNotNull { aud.optString(it).takeIf(String::isNotBlank) }
            else -> emptyList()
        }
        val rejection = ChatGptIdentityPolicy.validate(
            ChatGptIdentityClaimSet(
                issuer = claims.optString("iss"), audiences = audiences,
                authorizedParty = if (claims.has("azp")) claims.optString("azp") else null,
                expirationSeconds = claims.optLong("exp", 0),
                issuedAtSeconds = claims.optLong("iat", Long.MAX_VALUE),
                notBeforeSeconds = claims.optLong("nbf", 0),
                nonce = claims.optString("nonce"), subject = claims.optString("sub")
            ), expectedClient, nonce, now
        )
        if (rejection != null) throw Rejection(rejection)
        return claims
    }

    private fun requireIdentity(valid: Boolean, problem: ChatGptCloudProblem) {
        if (!valid) throw Rejection(problem)
    }

}
