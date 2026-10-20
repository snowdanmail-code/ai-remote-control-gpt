package com.example.airemote

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException

/** Actual Android HTTPS transport to an isolated synthetic server, never OpenAI.
 * Ephemeral TLS identity is generated in memory; it is NOT an APK signing key.
 * Trust is scoped to injected test connections; production TLS is unchanged.
 */
@RunWith(AndroidJUnit4::class)
class AndroidOAuthTlsTransportTest {
    @Before fun emulatorOnly() {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("Emulator")) {
            "Run synthetic transport tests only on an isolated emulator"
        }
    }

    private class Fixture : AutoCloseable {
        private val certificate = HeldCertificate.Builder().commonName("localhost")
            .addSubjectAlternativeName("localhost").build()
        private val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false); start() }
        var opens = 0
        val traces = mutableListOf<String>()
        fun request(body: ByteArray? = null, trusted: Boolean = true, timeout: Int = 3000): HttpsURLConnection =
            ChatGptCloudBridge.request(
                if (body == null) ChatGptAuthProtocol.JWKS else ChatGptAuthProtocol.TOKEN,
                if (body == null) "GET" else "POST",
                contentType = if (body == null) null else "application/x-www-form-urlencoded",
                body = body,
                connectionFactory = { _, _ ->
                    opens++
                    (server.url(if (body == null) "/jwks" else "/token").toUrl().openConnection() as HttpsURLConnection)
                        .apply { if (trusted) sslSocketFactory = clientTls.sslSocketFactory() }
                },
                traceSink = { traces.add(it) }, connectTimeoutMs = 3000, readTimeoutMs = timeout
            ) as HttpsURLConnection
        override fun close() { server.shutdown() }
    }

    private fun grant(redirect: String = "http://127.0.0.1:1455/auth/callback", verifier: String = "v".repeat(48)) =
        ChatGptAuthProtocol.form(listOf("grant_type" to "authorization_code", "code" to "fixture-code",
            "code_verifier" to verifier, "redirect_uri" to redirect)).toByteArray()

    @Test fun actualHttpsGetThenTokenPostSendsOneCode() {
        Fixture().use { f ->
            f.server.enqueue(MockResponse().setBody("{}"))
            f.server.enqueue(MockResponse().setBody("{\"access_token\":\"fixture-access\",\"token_type\":\"Bearer\",\"expires_in\":3600}"))
            f.request().let { c -> try { assertEquals(200,c.responseCode); c.inputStream.readBytes() } finally { c.disconnect() } }
            f.request(grant()).let { c -> try {
                assertEquals(200,c.responseCode)
                assertEquals("fixture-access",JSONObject(c.inputStream.bufferedReader().readText()).getString("access_token"))
            } finally { c.disconnect() } }
            assertEquals("GET",f.server.takeRequest(3,TimeUnit.SECONDS)!!.method)
            val post=f.server.takeRequest(3,TimeUnit.SECONDS)!!
            assertEquals("POST",post.method); assertEquals(String(grant()),post.body.readUtf8())
            assertEquals(2,f.server.requestCount); assertEquals(2,f.opens)
            assertFalse(f.traces.joinToString().contains("fixture-code"))
        }
    }

    @Test fun untrustedAndroidTlsSendsNoCodeAndDoesNotRetry() {
        Fixture().use { f ->
            assertThrows(SSLException::class.java) { f.request(grant(),trusted=false) }
            assertEquals(1,f.opens); assertEquals(0,f.server.requestCount)
        }
    }

    @Test fun dnsAtConnectionCreationIsBoundedBeforeBody() {
        var opens=0
        assertThrows(UnknownHostException::class.java) {
            ChatGptCloudBridge.request(ChatGptAuthProtocol.TOKEN,"POST",body=grant(),
                connectionFactory={_,_-> opens++; throw UnknownHostException("synthetic")},traceSink={})
        }
        assertEquals(2,opens)
    }

    @Test fun networkLossBeforePostDoesNotRetry() {
        var opens=0
        assertThrows(ConnectException::class.java) {
            ChatGptCloudBridge.request(ChatGptAuthProtocol.TOKEN,"POST",body=grant(),
                connectionFactory={_,_-> opens++; throw ConnectException("synthetic")},traceSink={})
        }
        assertEquals(1,opens)
    }

    @Test fun androidReadTimeoutAfterPostDoesNotReplay() {
        Fixture().use { f ->
            f.server.enqueue(MockResponse().setBody("fixture-token-response").setBodyDelay(2,TimeUnit.SECONDS))
            val c=f.request(grant(),timeout=500)
            try {
                assertEquals(200,c.responseCode)
                assertThrows(SocketTimeoutException::class.java) { c.inputStream.readBytes() }
            } finally { c.disconnect() }
            assertNotNull(f.server.takeRequest(3,TimeUnit.SECONDS))
            assertEquals(1,f.opens); assertEquals(1,f.server.requestCount)
        }
    }

    @Test fun server503AndPostDisconnectNeverReplayCode() {
        for (response in listOf(MockResponse().setResponseCode(503).setBody("{}"),
                MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))) {
            Fixture().use { f ->
                f.server.enqueue(response)
                if (response.socketPolicy == SocketPolicy.DISCONNECT_AFTER_REQUEST) {
                    assertThrows(IOException::class.java) {
                        val c=f.request(grant()); try { c.responseCode } finally { c.disconnect() }
                    }
                } else {
                    val c=f.request(grant()); try { assertEquals(503,c.responseCode) } finally { c.disconnect() }
                }
                assertNotNull(f.server.takeRequest(3,TimeUnit.SECONDS))
                assertEquals(1,f.opens); assertEquals(1,f.server.requestCount)
            }
        }
    }

    @Test fun mockTokenEndpointRejectsWrongRedirectAndPkce() {
        Fixture().use { f ->
            f.server.dispatcher=object: okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    val actual=request.body.readUtf8()
                    return if(actual==String(grant())) MockResponse().setBody("{}")
                    else MockResponse().setResponseCode(400).setBody("{\"error\":\"invalid_grant\"}")
                }
            }
            for (body in listOf(grant(redirect="http://127.0.0.1:1456/auth/callback"),grant(verifier="wrong"))) {
                val c=f.request(body)
                try { assertEquals(400,c.responseCode)
                    assertEquals("invalid_grant",JSONObject(c.errorStream.bufferedReader().readText()).getString("error"))
                } finally { c.disconnect() }
            }
            assertEquals(2,f.opens); assertEquals(2,f.server.requestCount)
        }
    }
}
