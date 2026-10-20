package com.example.airemote

import org.junit.Assert.*
import org.junit.Test
import org.junit.AfterClass
import java.net.UnknownHostException
import java.net.SocketTimeoutException
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLException

class ChatGptTokenTransportJvmTest {
    private val body="grant_type=authorization_code&code=fixture-once".toByteArray()
    private fun exchange(server:TlsOAuthServer,failedDnsFirst:Boolean=false):Pair<Int,Int> {
        val created=AtomicInteger()
        val conn=ChatGptTokenConnect.openBeforeBody(
            create={ created.incrementAndGet();TlsOAuthFixture.connection(server.port,"/token",body) },
            establish={ if(failedDnsFirst && created.get()==1) throw UnknownHostException("fixture");it.connect() },
            discard={ it.disconnect() },pauseBeforeRetry={}
        )
        return try { conn.outputStream.use { it.write(body) };Pair(conn.responseCode,created.get()) } finally { conn.disconnect() }
    }
    @Test fun httpsSuccessSendsExactlyOneBody() {
        TlsOAuthServer().use { server -> val result=exchange(server);assertEquals(200,result.first);assertEquals(1,result.second);assertEquals(1,server.receivedBodies.get()) }
    }
    @Test fun dnsRetryBeforeTlsSendsExactlyOneBody() {
        TlsOAuthServer().use { server -> val result=exchange(server,true);assertEquals(200,result.first);assertEquals(2,result.second);assertEquals(1,server.receivedBodies.get()) }
    }
    @Test fun http503DoesNotReplayBody() {
        TlsOAuthServer(503).use { server -> val result=exchange(server);assertEquals(503,result.first);assertEquals(1,result.second);assertEquals(1,server.receivedBodies.get()) }
    }
    @Test fun connectionLostAfterBodyDoesNotReplay() {
        TlsOAuthServer(closeAfterBody=true).use { server -> assertThrows(IOException::class.java) { exchange(server) };assertEquals(1,server.receivedBodies.get()) }
    }
    @Test fun untrustedTlsCannotSendBodyOrRetry() {
        TlsOAuthServer().use { server ->
            var creates=0
            assertThrows(SSLException::class.java) { ChatGptTokenConnect.openBeforeBody<javax.net.ssl.HttpsURLConnection>(
                create={ creates++;TlsOAuthFixture.connection(server.port,"/token",body,trustFixture=false) },
                establish={ it.connect() },discard={ it.disconnect() },pauseBeforeRetry={ fail("Retried TLS") }
            ) }
            assertEquals(1,creates);assertEquals(0,server.receivedBodies.get())
        }
    }
    @Test fun persistentDnsStopsAfterTwoAttemptsWithoutBody() {
        var creates=0;var discarded=0
        assertThrows(UnknownHostException::class.java) { ChatGptTokenConnect.openBeforeBody(
            create={ ++creates },establish={ throw UnknownHostException("fixture") },discard={ discarded++ },pauseBeforeRetry={}
        ) };assertEquals(2,creates);assertEquals(2,discarded)
    }
    @Test fun preBodyTimeoutIsNotRetried() {
        var creates=0
        assertThrows(SocketTimeoutException::class.java) { ChatGptTokenConnect.openBeforeBody(
            create={ ++creates },establish={ throw SocketTimeoutException("fixture") },discard={},pauseBeforeRetry={ fail("Retried timeout") }
        ) };assertEquals(1,creates)
    }
    companion object { @JvmStatic @AfterClass fun cleanupFixture() { TlsOAuthFixture.cleanup() } }
}
