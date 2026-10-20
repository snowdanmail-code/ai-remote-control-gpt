package com.example.airemote
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.*
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLHandshakeException

/** Only isolated local HTTPS fixtures. This does not authenticate a user or invoke an OpenAI model. */
class ChatGptMockFlowJvmTest {
    @Test fun callbackSignedIdentityCatalogResponseAndRotatingRefresh() {
        TlsOAuthFixture.server().use { server ->
            server.soTimeout=8000
            val failure=AtomicReference<Throwable?>()
            val worker=Thread {
                try { repeat(5) { index -> server.accept().use { socket ->
                    socket.soTimeout=5000
                    val reader=socket.getInputStream().bufferedReader()
                    val first=reader.readLine();var length=0
                    while(true) { val h=reader.readLine() ?: break; if(h.isEmpty()) break
                        if(h.startsWith("Content-Length:",true)) length=h.substringAfter(':').trim().toInt()
                    }
                    val chars=CharArray(length);var count=0
                    while(count<length) { val n=reader.read(chars,count,length-count);check(n>0);count+=n }
                    val body=String(chars)
                    val output=when(index) {
                        0 -> { check(first.startsWith("POST /token "));check(body.contains("grant_type=authorization_code"));check(body.contains("client_id=oaiapp_fixture"));check(body.contains("code_verifier="))
                            JSONObject().put("id_token",SignedTokenFixture.sign()).put("access_token","fixture-access").put("refresh_token","fixture-refresh").put("scope",ChatGptAuthProtocol.SCOPE).put("token_type","Bearer").put("expires_in",3600).toString() }
                        1 -> { check(first.startsWith("GET /jwks "));JSONObject().put("keys",SignedTokenFixture.keys()).toString() }
                        2 -> { check(first.startsWith("GET /models "));"""{"models":[{"visibility":"list","slug":"fixture-model"}]}""" }
                        3 -> { check(first.startsWith("POST /responses "));val json=JSONObject(body);check(!json.getBoolean("store")&&json.getBoolean("stream"))
                            "data: {\"type\":\"response.output_text.delta\",\"delta\":\"fixture answer\"}\n\ndata: {\"type\":\"response.completed\"}\n\n" }
                        else -> { check(first.startsWith("POST /token "));check(body.contains("grant_type=refresh_token"));check(body.contains("refresh_token=fixture-refresh"))
                            JSONObject().put("access_token","fixture-access-2").put("refresh_token","fixture-refresh-2").put("scope",ChatGptAuthProtocol.SCOPE).put("token_type","Bearer").put("expires_in",3600).toString() }
                    }
                    val bytes=output.toByteArray();socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray());write(bytes);flush()
                    }
                } } } catch(ex:Throwable) { failure.set(ex) }
            }.apply { isDaemon=true;start() }
            fun exchange(path:String,body:String?=null):String {
                val bytes=body?.toByteArray()
                val c = if(bytes!=null) ChatGptTokenConnect.openBeforeBody(
                    create={ TlsOAuthFixture.connection(server.localPort,path,bytes) },
                    establish={ it.connect() },discard={ it.disconnect() },pauseBeforeRetry={}
                ) else TlsOAuthFixture.connection(server.localPort,path)
                if(bytes!=null) c.outputStream.use { it.write(bytes) }
                return try { assertEquals(200,c.responseCode);c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
            }
            val state="s".repeat(32);val redirect="http://127.0.0.1:1455/auth/callback"
            val callback=ChatGptAuthProtocol.validateCallback("/auth/callback?state=$state&client_id=oaiapp_fixture&code=fixture-code",redirect,state,null)
            val token=JSONObject(exchange("/token",ChatGptAuthProtocol.form(listOf("grant_type" to "authorization_code","client_id" to callback.issuedClientId,"code" to callback.code,"code_verifier" to "v".repeat(48),"redirect_uri" to redirect,"resource" to ChatGptAuthProtocol.RESOURCE))))
            val keys=JSONObject(exchange("/jwks")).getJSONArray("keys")
            assertEquals("fixture-user",ChatGptIdTokenVerifier.verify(token.getString("id_token"),callback.issuedClientId,"n".repeat(32),keys).getString("sub"))
            assertTrue(ChatGptAuthProtocol.scopeAllowsInference(token.getString("scope")))
            assertEquals("fixture-model",ChatGptCloudBridge.chooseModel(JSONObject(exchange("/models"))))
            val stream=exchange("/responses",JSONObject().put("model","fixture-model").put("store",false).put("stream",true).put("input","test").toString())
            assertEquals("fixture answer",ChatGptCloudBridge.readCompletedResponse(stream.reader().buffered()))
            val refreshed=JSONObject(exchange("/token",ChatGptAuthProtocol.form(listOf("grant_type" to "refresh_token","client_id" to callback.issuedClientId,"refresh_token" to token.getString("refresh_token"),"resource" to ChatGptAuthProtocol.RESOURCE))))
            assertNotEquals(token.getString("refresh_token"),refreshed.getString("refresh_token"))
            worker.join(6000);assertFalse(worker.isAlive);failure.get()?.let { throw AssertionError(it) }
        }
    }
    @Test fun dnsTlsTimeoutAndServerFailureAreClassified() {
        assertEquals(ChatGptCloudProblem.DNS_FAILURE,ChatGptTransportDiagnostics.problem(UnknownHostException("private")))
        assertEquals(ChatGptCloudProblem.TLS_FAILURE,ChatGptTransportDiagnostics.problem(SSLHandshakeException("private")))
        assertEquals(ChatGptCloudProblem.TIMEOUT,ChatGptTransportDiagnostics.problem(SocketTimeoutException("private")))
        val error=ChatGptCloudBridge.classifiedHttpError(503,"""{"error":{"code":"subscription_sharing_usage_unavailable"}}""", "req_fixture123")
        assertEquals(503,error.httpCode);assertEquals("subscription_sharing_usage_unavailable",error.publicCode)
    }
}
