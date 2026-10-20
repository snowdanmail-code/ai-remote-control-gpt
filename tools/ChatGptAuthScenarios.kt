package com.example.airemote

import java.net.URI
import java.net.URLDecoder

object ChatGptAuthScenarios {
    @JvmStatic fun main(args: Array<String>) {
        var n = 0
        fun checkIt(ok: Boolean) { check(ok); n++ }
        val host = "urn:uuid:123e4567-e89b-42d3-a456-426614174000"
        val cb = "http://127.0.0.1:14551/auth/callback"
        val s = "a".repeat(40); val nonce = "b".repeat(40); val v = "c".repeat(44)
        val url = ChatGptAuthProtocol.authorizationUrl(host, cb, s, nonce, v)
        val query = URI(url).rawQuery.split('&').associate { val l=it.split('=',limit=2); l[0] to URLDecoder.decode(l[1], "UTF-8") }
        checkIt(query["response_type"] == "code")
        checkIt(query["client_id"] == "dynamic_agent_client")
        checkIt(query["agent_name_hint"] == "AI Remote Control GPT")
        checkIt(query["redirect_uri"] == cb)
        checkIt(query["code_challenge"] == ChatGptAuthProtocol.challenge(v))
        checkIt(query["resource"] == "https://api.openai.com/v1")
        checkIt(query["scope"]!!.contains("chatgpt.tokens.use.direct"))
        checkIt(query["nonce"] == nonce)
        checkIt(ChatGptAuthProtocol.isLoopbackCallback(cb))
        for (bad in listOf("https://127.0.0.1:14551/auth/callback", "http://localhost:14551/auth/callback", "http://127.0.0.1:14551/bad", "http://127.0.0.1:80/auth/callback", "http://evil.com/auth/callback")) {
            checkIt(!ChatGptAuthProtocol.isLoopbackCallback(bad))
        }
        val ret = ChatGptAuthProtocol.validateCallback("/auth/callback?state=$s&code=secure123456&client_id=oaiapp_123",cb,s,null)
        checkIt(ret.code == "secure123456" && ret.issuedClientId == "oaiapp_123")
        val ret2 = ChatGptAuthProtocol.validateCallback("/auth/callback?state=$s&code=secure123456",cb,s,"oaiapp_saved")
        checkIt(ret2.issuedClientId == "oaiapp_saved")
        fun mustFail(block:()->Unit) { var threw=false; try{block()}catch(_:Exception){threw=true}; checkIt(threw) }
        mustFail { ChatGptAuthProtocol.validateCallback("/auth/callback?state=wrong&code=secure123456&client_id=oaiapp_x",cb,s,null) }
        mustFail { ChatGptAuthProtocol.validateCallback("/auth/callback?state=$s&code=secure123456&client_id=dynamic_agent_client",cb,s,null) }
        mustFail { ChatGptAuthProtocol.validateCallback("/auth/callback?state=$s&code=secure123456&client_id=oaiapp_evil",cb,s,"oaiapp_saved") }
        mustFail { ChatGptAuthProtocol.validateCallback("/auth/callback?state=$s&state=$s&code=secure123456&client_id=oaiapp_x",cb,s,null) }
        mustFail { ChatGptAuthProtocol.validateCallback("/auth/callback?state=$s&error=access_denied&code=secure123456&client_id=oaiapp_x",cb,s,null) }
        mustFail { ChatGptAuthProtocol.validateCallback("/wrong?state=$s&code=secure123456&client_id=oaiapp_x",cb,s,null) }
        checkIt(ChatGptAuthProtocol.scopeAllowsInference("openid resource.invoke chatgpt.tokens.use.direct"))
        checkIt(!ChatGptAuthProtocol.scopeAllowsInference("openid profile email"))
        checkIt(!ChatGptAuthProtocol.scopeAllowsInference("resource.invoke"))
        val r = ChatGptAuthProtocol.authorizationUrl(host,cb,s,nonce,v,"oaiapp_saved")
        checkIt(!r.contains("agent_name_hint="))
        checkIt(r.contains("oaiapp_saved"))
        checkIt(ChatGptAuthProtocol.randomUrlSafe() != ChatGptAuthProtocol.randomUrlSafe())
        mustFail { ChatGptAuthProtocol.inferenceInput(listOf("")) }
        checkIt(ChatGptAuthProtocol.inferenceInput(listOf("hello")).single() == "hello")
        println("ChatGPT OAuth protocol checks: $n passed")
    }
}
