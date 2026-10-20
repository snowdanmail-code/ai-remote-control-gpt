package com.example.airemote

/** Fixed fields only: no URL, hostname, exception message, headers, body or credentials. */
class ChatGptTransportTrace(private val clock: () -> Long = System::nanoTime) {
    enum class Phase { CREATE, CONNECT_TLS, BODY_OPEN, BODY_WRITE, RESPONSE_HEADERS, RESPONSE_READ }
    enum class Network { WIFI, CELLULAR, ETHERNET, VPN, OTHER, UNKNOWN }
    private val started = clock()
    var phase = Phase.CREATE
    var bodyStarted = false
    var attempt = 1
    var network = Network.UNKNOWN
    var proxy = false
    fun snapshot(error: Throwable? = null): String {
        fun kind(t: Throwable?): String = when(t) {
            null -> "NONE"
            is java.net.UnknownHostException -> "UnknownHostException"
            is javax.net.ssl.SSLException -> "SSLException"
            is java.net.SocketTimeoutException -> "SocketTimeoutException"
            is java.net.ConnectException -> "ConnectException"
            is java.net.NoRouteToHostException -> "NoRouteToHostException"
            is java.net.SocketException -> "SocketException"
            is java.io.IOException -> "IOException"
            is SecurityException -> "SecurityException"
            else -> "OTHER"
        }
        return "phase=${phase.name} elapsed_ms=${((clock()-started)/1_000_000).coerceAtLeast(0)} attempt=$attempt network=${network.name} proxy=$proxy body_started=$bodyStarted exception=${kind(error)} cause=${kind(error?.cause)}"
    }
}
