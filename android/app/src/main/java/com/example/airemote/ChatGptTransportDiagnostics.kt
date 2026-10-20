package com.example.airemote

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Maps transport exceptions to stable non-secret codes. Never expose exception messages or URLs. */
object ChatGptTransportDiagnostics {
    fun problem(error: Throwable): ChatGptCloudProblem = when (error) {
        is UnknownHostException -> ChatGptCloudProblem.DNS_FAILURE
        is NoRouteToHostException, is ConnectException -> ChatGptCloudProblem.CONNECT_FAILURE
        is SSLException -> ChatGptCloudProblem.TLS_FAILURE
        is SocketTimeoutException -> ChatGptCloudProblem.TIMEOUT
        is SocketException, is java.io.IOException -> ChatGptCloudProblem.TRANSPORT_FAILURE
        else -> ChatGptCloudProblem.OTHER
    }
}
