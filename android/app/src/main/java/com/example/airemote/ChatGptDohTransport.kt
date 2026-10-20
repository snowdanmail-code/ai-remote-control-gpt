package com.example.airemote

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.dnsoverhttps.DnsOverHttps
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Optional secondary network path for the official OpenAI OAuth / Responses endpoints.
 *
 * No API key, OAuth code, refresh token or request body is ever sent to the DNS
 * provider. Cloudflare or Google receives ONLY a DNS lookup for an OpenAI hostname.
 * HTTPS still connects to the original OpenAI hostname with normal TLS verification.
 * No redirects, no untrusted certificates and NO automatic POST replays.
 */
internal object ChatGptDohTransport {
    data class Reply(val status: Int, val body: String, val requestId: String?)

    fun validateTarget(raw: String): URL = URL(raw).also { url ->
        require(url.protocol == "https" && url.host in setOf("auth.openai.com", "api.openai.com"))
        require(url.userInfo == null && url.ref == null && url.port in listOf(-1, 443))
        require(url.query == null)
    }

    private fun resolver(hostName: String, ip: ByteArray, endpoint: String): DnsOverHttps {
        val bootstrap = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(7, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .build()
        return DnsOverHttps.Builder()
            .client(bootstrap)
            .url(endpoint.toHttpUrl())
            .bootstrapDnsHosts(InetAddress.getByAddress(ip))
            .build()
    }

    /**
     * Resolve host BEFORE opening a connection or writing an OAuth grant.
     * If one resolver fails, trying the other is safe because neither has
     * been given a credential-bearing request.
     */
    private fun resolve(host: String): List<InetAddress> {
        val sources = listOf(
            Pair(byteArrayOf(1, 1, 1, 1), "https://cloudflare-dns.com/dns-query"),
            Pair(byteArrayOf(8, 8, 8, 8), "https://dns.google/dns-query")
        )
        var last: Exception? = null
        for ((ip, endpoint) in sources) {
            try {
                val addresses = resolver(host, ip, endpoint).lookup(host)
                if (addresses.isNotEmpty()) {
                    // A single connection per request: choose an IPv4 answer
                    // when available; otherwise use the first IPv6 address.
                    return addresses.filterIsInstance<Inet4Address>().ifEmpty { addresses }.take(1)
                }
            } catch (ex: Exception) { last = ex }
        }
        throw UnknownHostException("Secure DNS could not resolve the OpenAI endpoint").apply {
            if (last != null) initCause(last)
        }
    }

    fun call(
        url: String, method: String, bearer: String? = null,
        contentType: String? = null, body: ByteArray? = null,
        maxBytes: Int = 1_000_000
    ): Reply {
        val target = validateTarget(url)
        require(method == "GET" || method == "POST")
        require((method == "POST") == (body != null))
        require(body == null || body.size < 128_000)
        require(maxBytes in 1..1_000_000)

        val addresses = resolve(target.host)
        val pinnedDns = Dns { host ->
            if (host != target.host) throw UnknownHostException("Unexpected DNS hostname")
            addresses
        }
        val client = OkHttpClient.Builder()
            .dns(pinnedDns)
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(35, TimeUnit.SECONDS)
            .callTimeout(50, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        val builder = Request.Builder().url(target.toString())
            .header("Accept", if (url.endsWith("/v1/responses")) "text/event-stream" else "application/json")
        if (bearer != null) builder.header("Authorization", "Bearer $bearer")
        if (method == "POST") {
            builder.post(body!!.toRequestBody((contentType ?: "application/octet-stream").toMediaType()))
        } else builder.get()

        // A POST is issued at most ONCE. Network errors after sending may
        // never trigger a second code/refresh/inference submission.
        return client.newCall(builder.build()).execute().use { response ->
            val input = response.body?.byteStream()
            val output = ByteArrayOutputStream()
            if (input != null) {
                val buffer = ByteArray(8192)
                input.use {
                    while (true) {
                        val read = it.read(buffer)
                        if (read == -1) break
                        require(output.size() + read <= maxBytes) { "Oversized OpenAI response" }
                        output.write(buffer, 0, read)
                    }
                }
            }
            Reply(response.code, output.toString("UTF-8"), response.header("x-request-id"))
        }
    }
}
