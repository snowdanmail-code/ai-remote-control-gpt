package com.example.airemote

object ChatGptLoginPreflightScenarios {
    @JvmStatic fun main(args: Array<String>) {
        var n = 0
        fun verify(a: String, b: String, ok: Boolean, expected: ChatGptCloudProblem? = null) {
            val result = ChatGptLoginPreflight.assess(a,b)
            check(result.canOpenBrowser == ok) { "Unexpected authorization preflight: $a / $b" }
            check(result.problem == expected)
            n++
        }
        val auth = "DNS_OK HTTPS_REACHABLE HTTP_200"
        val api = "DNS_OK HTTPS_REACHABLE HTTP_401"
        verify(auth, api, true)
        verify(auth, "DNS_OK HTTPS_REACHABLE HTTP_403", true)
        verify(auth, "DNS_OK HTTPS_REACHABLE HTTP_200", true)
        verify("DNS_OK HTTPS_REACHABLE HTTP_204", api, true)
        verify("DNS_DNS_FAILURE", api, false, ChatGptCloudProblem.DNS_FAILURE)
        verify("DNS_OK HTTPS_TLS_FAILURE", api, false, ChatGptCloudProblem.TLS_FAILURE)
        verify(auth, "DNS_OK HTTPS_TLS_FAILURE", false, ChatGptCloudProblem.TLS_FAILURE)
        verify("DNS_OK HTTPS_TIMEOUT", api, false, ChatGptCloudProblem.TIMEOUT)
        verify(auth, "DNS_OK HTTPS_CONNECT_FAILURE", false, ChatGptCloudProblem.CONNECT_FAILURE)
        verify(auth, "DNS_OK HTTPS_REACHABLE HTTP_520", false, ChatGptCloudProblem.TRANSPORT_FAILURE)
        verify("DNS_OK HTTPS_REACHABLE HTTP_302", api, false, ChatGptCloudProblem.TRANSPORT_FAILURE)
        verify(auth, "DNS_OK HTTPS_REACHABLE HTTP_429", false, ChatGptCloudProblem.TRANSPORT_FAILURE)
        verify("DNS_OK HTTPS_REACHABLE HTTP_401", api, false, ChatGptCloudProblem.TRANSPORT_FAILURE)
        verify("DNS_EMPTY", api, false, ChatGptCloudProblem.DNS_FAILURE)
        verify(auth, "DNS_OK HTTPS_NO_RESPONSE", false, ChatGptCloudProblem.TRANSPORT_FAILURE)
        verify(auth, "DNS_OK HTTPS_REACHABLE HTTP_502", false, ChatGptCloudProblem.TRANSPORT_FAILURE)
        println("CHATGPT_LOGIN_PREFLIGHT_OK=$n")
    }
}
