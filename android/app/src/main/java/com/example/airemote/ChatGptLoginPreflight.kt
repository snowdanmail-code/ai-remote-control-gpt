package com.example.airemote

/**
 * Only network reachability, never a promise that OAuth or ChatGPT consent succeeds.
 * No credentials, browser state, or account identifiers are used by this gate.
 */
object ChatGptLoginPreflight {
    data class Result(val canOpenBrowser: Boolean, val problem: ChatGptCloudProblem?)

    fun assess(authProbe: String, apiProbe: String): Result {
        // OpenAI JWKS should be a direct 2xx HTTPS response, not a redirect/captive portal.
        if (!authProbe.startsWith("DNS_OK HTTPS_REACHABLE HTTP_2")) {
            return Result(false, classify(authProbe))
        }
        // The unauthenticated model-list endpoint normally returns 401/403. The
        // purpose here is HTTPS reachability, not authorization to use models.
        if (!apiProbe.startsWith("DNS_OK HTTPS_REACHABLE HTTP_2") &&
            !apiProbe.startsWith("DNS_OK HTTPS_REACHABLE HTTP_401") &&
            !apiProbe.startsWith("DNS_OK HTTPS_REACHABLE HTTP_403")) {
            return Result(false, classify(apiProbe))
        }
        return Result(true, null)
    }

    private fun classify(probe: String): ChatGptCloudProblem = when {
        probe.startsWith("DNS_") && !probe.startsWith("DNS_OK") -> ChatGptCloudProblem.DNS_FAILURE
        "DNS_FAILURE" in probe -> ChatGptCloudProblem.DNS_FAILURE
        "TLS_FAILURE" in probe -> ChatGptCloudProblem.TLS_FAILURE
        "TIMEOUT" in probe -> ChatGptCloudProblem.TIMEOUT
        "CONNECT_FAILURE" in probe -> ChatGptCloudProblem.CONNECT_FAILURE
        else -> ChatGptCloudProblem.TRANSPORT_FAILURE
    }
}
