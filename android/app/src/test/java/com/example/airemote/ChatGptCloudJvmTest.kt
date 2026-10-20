package com.example.airemote

import org.junit.Assert.*
import org.junit.Test
import java.io.BufferedReader
import java.io.StringReader

class ChatGptCloudJvmTest {
    private fun response(s: String) = ChatGptCloudBridge.readCompletedResponse(BufferedReader(StringReader(s)))
    @Test fun completedIsTerminalEvenIfTransportRemainsOpen() {
        val lines = ArrayDeque(listOf("data: {\"type\":\"response.output_text.delta\",\"delta\":\"OK\"}",
            "data: {\"type\":\"response.completed\"}"))
        val reader = object : BufferedReader(StringReader("")) {
            override fun readLine(): String = if (lines.isEmpty()) error("Read after terminal event") else lines.removeFirst()
        }
        assertEquals("OK", ChatGptCloudBridge.readCompletedResponse(reader))
    }
    @Test fun acceptsDataWithoutSpace() {
        assertEquals("OK", response("data:{\"type\":\"response.output_text.delta\",\"delta\":\"OK\"}\ndata:{\"type\":\"response.completed\"}\n"))
    }
    @Test fun completedCanSupplyFinalText() {
        assertEquals("OK", response("data: {\"type\":\"response.completed\",\"response\":{\"output\":[{\"content\":[{\"type\":\"output_text\",\"text\":\"OK\"}]}]}}\n"))
    }
    @Test fun partialAndFailedStreamsNeverReportSuccess() {
        for (tail in listOf("", "data: [DONE]\n", "data: {\"type\":\"response.failed\"}\n", "data: {\"type\":\"response.incomplete\"}\n")) {
            try { response("data: {\"type\":\"response.output_text.delta\",\"delta\":\"partial\"}\n" + tail); fail("Accepted partial stream") }
            catch (_: IllegalStateException) {}
        }
    }
    @Test fun emptyCompletedResponseIsNotConnectionProof() {
        val ex = assertThrows(ChatGptCloudBridge.CloudProblemError::class.java) {
            response("data: {\"type\":\"response.completed\"}\n")
        }
        assertEquals(ChatGptCloudProblem.EMPTY_RESPONSE, ex.problem)
    }
    @Test fun rfcPkceVector() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", ChatGptAuthProtocol.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }
    @Test fun callbackRejectsStateReplayAndDuplicateParameters() {
        val redirect = "http://127.0.0.1:34567/auth/callback"
        val state = "s".repeat(32)
        for (path in listOf("/auth/callback?code=abcdefgh&state=wrong&client_id=oaiapp_test",
            "/auth/callback?code=abcdefgh&state=$state&state=$state&client_id=oaiapp_test",
            "/auth/callback?code=abcdefgh&state=$state&client_id=dynamic_agent_client")) {
            try { ChatGptAuthProtocol.validateCallback(path, redirect, state, null); fail("Invalid callback accepted") }
            catch (_: IllegalArgumentException) {}
        }
        assertEquals("oaiapp_test", ChatGptAuthProtocol.validateCallback("/auth/callback?code=abcdefgh&state=$state&client_id=oaiapp_test",redirect,state,null).issuedClientId)
    }
    @Test fun identityScopeIsNotInferencePermission() {
        assertFalse(ChatGptAuthProtocol.scopeAllowsInference("openid profile email"))
        assertTrue(ChatGptAuthProtocol.scopeAllowsInference(ChatGptAuthProtocol.SCOPE))
    }
}
