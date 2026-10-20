package com.example.airemote

object ChatGptIdentityPolicyScenarios {
    @JvmStatic fun main(args: Array<String>) {
        var n = 0
        val good = ChatGptIdentityClaimSet(
            issuer = ChatGptAuthProtocol.ISSUER,
            audiences = listOf("oaiapp_active"), authorizedParty = null,
            expirationSeconds = 3000, issuedAtSeconds = 1000, notBeforeSeconds = 1000,
            nonce = "fresh_random_nonce", subject = "opaque_subject"
        )
        fun test(v: ChatGptIdentityClaimSet, expected: ChatGptCloudProblem? = null,
                 client: String = "oaiapp_active", nonce: String = "fresh_random_nonce", now: Long = 1200) {
            val got = ChatGptIdentityPolicy.validate(v, client, nonce, now)
            check(got == expected) { "Expected $expected, got $got" }
            n++
        }
        test(good)
        test(good.copy(audiences = listOf("other", "oaiapp_active")), ChatGptCloudProblem.INVALID_AUDIENCE)
        test(good.copy(audiences = listOf("other", "oaiapp_active"), authorizedParty = "oaiapp_active"))
        test(good.copy(authorizedParty = "oaiapp_active"))
        test(good.copy(issuer = "https://phishing.example"), ChatGptCloudProblem.INVALID_ISSUER)
        test(good.copy(audiences = emptyList()), ChatGptCloudProblem.INVALID_AUDIENCE)
        test(good.copy(audiences = listOf("oaiapp_other")), ChatGptCloudProblem.INVALID_AUDIENCE)
        test(good.copy(authorizedParty = "oaiapp_other"), ChatGptCloudProblem.INVALID_AUDIENCE)
        test(good, ChatGptCloudProblem.INVALID_AUDIENCE, client = "")
        test(good.copy(expirationSeconds = 1200), ChatGptCloudProblem.INVALID_ID_TIME)
        test(good.copy(issuedAtSeconds = 1261), ChatGptCloudProblem.INVALID_ID_TIME)
        test(good.copy(notBeforeSeconds = 1261), ChatGptCloudProblem.INVALID_ID_TIME)
        test(good.copy(issuedAtSeconds = 0), ChatGptCloudProblem.INVALID_ID_TIME)
        test(good.copy(nonce = "other_nonce"), ChatGptCloudProblem.INVALID_NONCE)
        test(good, ChatGptCloudProblem.INVALID_NONCE, nonce = "other_nonce")
        test(good.copy(subject = ""), ChatGptCloudProblem.INVALID_SUBJECT)
        test(good.copy(subject = "   "), ChatGptCloudProblem.INVALID_SUBJECT)
        test(good, now = 2999)
        test(good.copy(notBeforeSeconds = 1260))
        check(ChatGptCloudDiagnostics.description(ChatGptCloudStep.IDENTITY, ChatGptCloudProblem.DNS_FAILURE)
            .contains("AUTH_IDENTITY_DNS_FAILURE")); n++
        check(ChatGptCloudDiagnostics.description(ChatGptCloudStep.IDENTITY, ChatGptCloudProblem.INVALID_NONCE)
            .contains("AUTH_IDENTITY_INVALID_NONCE")); n++
        println("CHATGPT_IDENTITY_POLICY_OK=$n")
    }
}
