package com.example.airemote

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection

/** Real local TLS sockets, production request path, synthetic grants only. */
class OAuth042SocketTest {
    private class Server(val delay:Boolean=false):AutoCloseable {
        val listener=TlsOAuthFixture.server().apply { soTimeout=200 }
        val port get()=listener.localPort
        val posts=AtomicInteger();val gets=AtomicInteger()
        private var used=false
        val worker=Thread {
            while(!listener.isClosed) try { listener.accept().use { socket ->
                socket.soTimeout=1000
                val reader=socket.getInputStream().bufferedReader();val first=reader.readLine() ?: return@use
                var size=0
                while(true) { val line=reader.readLine() ?: break;if(line.isEmpty())break
                    if(line.startsWith("Content-Length:",true))size=line.substringAfter(':').trim().toInt()
                }
                val buffer=CharArray(size);var count=0
                while(count<size) { val n=reader.read(buffer,count,size-count);if(n<0)return@use;count+=n }
                val body=String(buffer)
                var status=200
                val response=if(first.startsWith("GET /jwks ")) { gets.incrementAndGet();"{}" }
                else if(first.startsWith("POST /token ")) {
                    posts.incrementAndGet()
                    val grant=body.split('&').associate { val p=it.split('=',limit=2);java.net.URLDecoder.decode(p[0],"UTF-8") to java.net.URLDecoder.decode(p.getOrElse(1){""},"UTF-8") }
                    if(used || grant["redirect_uri"]!="http://127.0.0.1:1455/auth/callback" || grant["code_verifier"]!="v".repeat(48) || grant["code"]!="fixture-code") {
                        status=400;"{\"error\":\"invalid_grant\"}"
                    } else { used=true;"{\"access_token\":\"fixture-access\",\"refresh_token\":\"fixture-refresh\",\"expires_in\":3600,\"token_type\":\"Bearer\"}" }
                } else { status=405;"{\"error\":\"invalid_request\"}" }
                val bytes=response.toByteArray()
                socket.getOutputStream().apply {
                    write("HTTP/1.1 $status Fixture\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray());flush()
                    if(delay) Thread.sleep(400)
                    write(bytes);flush()
                }
            } } catch(_:SocketTimeoutException) {} catch(_:java.io.IOException) { /* close and client timeout are intentional */ }
        }.apply { isDaemon=true;start() }
        override fun close() { listener.close();worker.join(2000);check(!worker.isAlive) }
    }
    private fun grant(redirect:String="http://127.0.0.1:1455/auth/callback",verifier:String="v".repeat(48))=ChatGptAuthProtocol.form(listOf("grant_type" to "authorization_code","code" to "fixture-code","code_verifier" to verifier,"redirect_uri" to redirect)).toByteArray()
    private fun connect(server:Server,path:String,body:ByteArray?=null,timeout:Int=1500):HttpsURLConnection = ChatGptCloudBridge.request(
        if(body==null) ChatGptAuthProtocol.JWKS else ChatGptAuthProtocol.TOKEN,
        if(body==null) "GET" else "POST",body=body,
        connectionFactory={_,_->TlsOAuthFixture.connection(server.port,path,body)},traceSink={},readTimeoutMs=timeout
    ) as HttpsURLConnection
    @Test fun get200ThenActualPostAndNoReplayAfterCompletedBody() {
        Server().use { s ->
            connect(s,"/jwks").let { try { assertEquals(200,it.responseCode);it.inputStream.close() } finally { it.disconnect() } }
            connect(s,"/token",grant()).let { try { assertEquals(200,it.responseCode);val token=JSONObject(it.inputStream.bufferedReader().readText());assertEquals("fixture-access",token.getString("access_token")) } finally { it.disconnect() } }
            connect(s,"/token",grant()).let { try { assertEquals(400,it.responseCode);assertEquals("invalid_grant",JSONObject(it.errorStream.bufferedReader().readText()).getString("error")) } finally { it.disconnect() } }
            assertEquals(1,s.gets.get());assertEquals(2,s.posts.get())
        }
    }
    @Test fun exactRedirectAndPkceAreEnforcedByMockTokenEndpoint() {
        for(body in listOf(grant(redirect="http://127.0.0.1:1456/auth/callback"),grant(verifier="wrong"))) Server().use { s ->
            connect(s,"/token",body).let { try { assertEquals(400,it.responseCode) } finally { it.disconnect() } }
            assertEquals(1,s.posts.get())
        }
    }
    @Test fun readTimeoutAfterPostDoesNotReplay() {
        Server(delay=true).use { s ->
            val c=connect(s,"/token",grant(),80)
            try { assertEquals(200,c.responseCode);assertThrows(SocketTimeoutException::class.java) { c.inputStream.readBytes() } } finally { c.disconnect() }
            assertEquals(1,s.posts.get())
        }
    }
}
