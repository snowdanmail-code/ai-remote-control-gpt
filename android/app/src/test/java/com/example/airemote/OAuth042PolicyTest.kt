package com.example.airemote
import org.junit.Assert.*
import org.junit.Test
import java.net.UnknownHostException
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException

class OAuth042PolicyTest {
    @Test fun expiryTypeAndOverflowRejected() {
        assertEquals(3601000L,ChatGptTokenResponsePolicy.expiresAt(1000L,3600L,"Bearer"))
        for(n in listOf(0L,-1L,Long.MAX_VALUE)) assertThrows(IllegalArgumentException::class.java) { ChatGptTokenResponsePolicy.expiresAt(1000L,n,"Bearer") }
        assertThrows(IllegalArgumentException::class.java) { ChatGptTokenResponsePolicy.expiresAt(1L,1L,"Basic") }
    }
    @Test fun identityBindingRejectsChangedSubAndClient() {
        assertTrue(ChatGptTokenResponsePolicy.sameIdentity("clientA","subA","clientA","subA"))
        assertFalse(ChatGptTokenResponsePolicy.sameIdentity("clientA","subB","clientA","subA"))
        assertFalse(ChatGptTokenResponsePolicy.sameIdentity("clientB","subA","clientA","subA"))
    }
    @Test fun pendingRefreshAfterRestartRequiresNewAuthorization() {
        assertTrue(ChatGptTokenResponsePolicy.requiresReauthorization(true))
        assertFalse(ChatGptTokenResponsePolicy.requiresReauthorization(false))
    }
    @Test fun invalidGrantCannotBeReusedButInvalidClientIsDistinct() {
        assertTrue(ChatGptTokenResponsePolicy.unusableRefresh("invalid_grant"))
        assertFalse(ChatGptTokenResponsePolicy.unusableRefresh("invalid_client"))
        assertFalse(ChatGptTokenResponsePolicy.unusableRefresh(null))
    }
    @Test fun traceNeverContainsExceptionMessageOrCauseMessage() {
        val t=ChatGptTransportTrace { 100L };t.phase=ChatGptTransportTrace.Phase.CREATE
        val msg=t.snapshot(UnknownHostException("secret-code-host").apply { initCause(java.io.IOException("Bearer-secret")) })
        assertTrue(msg.contains("phase=CREATE"));assertTrue(msg.contains("body_started=false"))
        assertTrue(msg.contains("exception=UnknownHostException"));assertTrue(msg.contains("cause=IOException"))
        assertFalse(msg.contains("secret"));assertFalse(msg.contains("Bearer"))
    }
    private class Fake(val error: Exception?=null):HttpURLConnection(URL("https://auth.openai.com/api/accounts/oauth/token")) {
        var bodies=0
        override fun connect() { error?.let { throw it } }
        override fun disconnect() {}
        override fun usingProxy()=false
        override fun getOutputStream():java.io.OutputStream { bodies++;throw UnknownHostException("private-code") }
    }
    @Test fun bodyOpenFailureNeverReplaysAndTraceMarksStarted() {
        var created=0;val lines=mutableListOf<String>();val f=Fake()
        assertThrows(UnknownHostException::class.java) {
            ChatGptCloudBridge.request("https://auth.openai.com/api/accounts/oauth/token","POST",body="synthetic".toByteArray(),connectionFactory={_,_->created++;f},traceSink={lines.add(it)})
        }
        assertEquals(1,created);assertEquals(1,f.bodies)
        assertTrue(lines.last().contains("phase=BODY_OPEN"));assertTrue(lines.last().contains("body_started=true"))
    }
    @Test fun productionFactoryCreationFailureIsCoveredByBoundedDnsRetry() {
        var created=0;val lines=mutableListOf<String>()
        assertThrows(UnknownHostException::class.java) {
            ChatGptCloudBridge.request("https://auth.openai.com/api/accounts/oauth/token","POST",body="synthetic".toByteArray(),connectionFactory={_,_->created++;throw UnknownHostException("private")},traceSink={lines.add(it)})
        }
        assertEquals(2,created);assertTrue(lines.last().contains("phase=CREATE"));assertTrue(lines.last().contains("body_started=false"))
    }
    @Test fun tlsAndConnectTimeoutNeverRetryOrStartBody() {
        for(e in listOf(SSLHandshakeException("private"),SocketTimeoutException("private"))) {
            var count=0;val f=Fake(e)
            assertThrows(e.javaClass) { ChatGptCloudBridge.request("https://auth.openai.com/api/accounts/oauth/token","POST",body=byteArrayOf(1),connectionFactory={_,_->count++;f},traceSink={}) }
            assertEquals(1,count);assertEquals(0,f.bodies)
        }
    }
    @Test fun externalHttpProxyEndpointsAndCredentialsAreRejected() {
        for(url in listOf("http://auth.openai.com/token","https://evil.test/token","https://user@auth.openai.com/token","https://auth.openai.com:444/token")) {
            assertThrows(IllegalArgumentException::class.java) { ChatGptCloudBridge.request(url,"POST",body=byteArrayOf(1)) }
        }
    }
    @Test fun newLoginInvalidatesOldLogoutStatusGeneration() {
        val gate=ChatGptSessionGate()
        gate.invalidate();val oldLogout=gate.generation()
        assertTrue(gate.tryBeginSignIn())
        assertNotEquals(oldLogout,gate.generation())
    }
    @Test fun cleanupFailureDoesNotHideOriginalDnsOrAllowExtraRetries() {
        var attempts=0
        val error=assertThrows(UnknownHostException::class.java) {
            ChatGptTokenConnect.openBeforeBody(create={++attempts}, establish={throw UnknownHostException("secret")}, discard={throw java.io.IOException("secret-disconnect")},pauseBeforeRetry={})
        }
        assertEquals(2,attempts);assertEquals("secret",error.message)
    }

}
