package com.example.airemote

/** Pure, testable OIDC claim checks. Always applied AFTER validating RS256 signature.
 * Never relax issuer, client audience, expiry or the per-attempt nonce.
 */
data class ChatGptIdentityClaimSet(
    val issuer: String,
    val audiences: List<String>,
    val authorizedParty: String?,
    val expirationSeconds: Long,
    val issuedAtSeconds: Long,
    val notBeforeSeconds: Long,
    val nonce: String,
    val subject: String
)

object ChatGptIdentityPolicy {
    fun validate(claims: ChatGptIdentityClaimSet, expectedClient: String,
                 expectedNonce: String, nowSeconds: Long): ChatGptCloudProblem? {
        if (claims.issuer != ChatGptAuthProtocol.ISSUER) return ChatGptCloudProblem.INVALID_ISSUER
        if (expectedClient.isBlank() || expectedClient !in claims.audiences ||
            (claims.audiences.size > 1 && claims.authorizedParty == null) ||
            (claims.authorizedParty != null && claims.authorizedParty != expectedClient))
            return ChatGptCloudProblem.INVALID_AUDIENCE
        if (claims.expirationSeconds <= nowSeconds ||
            claims.issuedAtSeconds > nowSeconds + 60 ||
            claims.notBeforeSeconds > nowSeconds + 60 || claims.issuedAtSeconds <= 0)
            return ChatGptCloudProblem.INVALID_ID_TIME
        if (!ChatGptAuthProtocol.secureEquals(expectedNonce, claims.nonce))
            return ChatGptCloudProblem.INVALID_NONCE
        if (claims.subject.isBlank()) return ChatGptCloudProblem.INVALID_SUBJECT
        return null
    }
}
