package com.example.airemote

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

object ChatGptTransportScenarios {
    @JvmStatic fun main(args: Array<String>) {
        var passed = 0
        fun verify(error: Throwable, expected: ChatGptCloudProblem) {
            check(ChatGptTransportDiagnostics.problem(error) == expected)
            passed++
        }
        verify(UnknownHostException("do-not-expose-host"), ChatGptCloudProblem.DNS_FAILURE)
        verify(ConnectException("do-not-expose-address"), ChatGptCloudProblem.CONNECT_FAILURE)
        verify(NoRouteToHostException(), ChatGptCloudProblem.CONNECT_FAILURE)
        verify(SSLHandshakeException("never-disable-TLS"), ChatGptCloudProblem.TLS_FAILURE)
        verify(SocketTimeoutException(), ChatGptCloudProblem.TIMEOUT)
        verify(SocketException(), ChatGptCloudProblem.TRANSPORT_FAILURE)
        verify(java.io.IOException(), ChatGptCloudProblem.TRANSPORT_FAILURE)
        verify(IllegalStateException(), ChatGptCloudProblem.OTHER)
        for (p in listOf(ChatGptCloudProblem.DNS_FAILURE, ChatGptCloudProblem.CONNECT_FAILURE,
            ChatGptCloudProblem.TLS_FAILURE, ChatGptCloudProblem.TRANSPORT_FAILURE)) {
            val text = ChatGptCloudDiagnostics.description(ChatGptCloudStep.TOKEN, p)
            check(text.contains("AUTH_TOKEN_${p.name}"))
            check("do-not-expose" !in text)
            passed++
        }
        println("CHATGPT_TRANSPORT_DIAGNOSTICS_OK=$passed")
    }
}
