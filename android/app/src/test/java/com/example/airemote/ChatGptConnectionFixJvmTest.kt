package com.example.airemote
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class ChatGptConnectionFixJvmTest {
    @Test fun consentIsOnlyForcedByExplicitOperatorAction() {
        val args = arrayOf("urn:uuid:test", "http://127.0.0.1:1455/auth/callback", "s".repeat(32), "n".repeat(32), "v".repeat(48), "oaiapp_saved")
        val ordinary = ChatGptAuthProtocol.authorizationUrl(args[0], args[1], args[2], args[3], args[4], args[5])
        val explicit = ChatGptAuthProtocol.authorizationUrl(args[0], args[1], args[2], args[3], args[4], args[5], true)
        assertFalse(ordinary.contains("prompt=")); assertTrue(explicit.contains("prompt=consent"))
    }
    @Test fun requestCannotStartDuringSignIn() {
        val gate = ChatGptSessionGate()
        assertTrue(gate.tryBeginSignIn()); assertTrue(gate.isSigningIn())
        assertFalse(gate.tryBeginRequest()); assertFalse(gate.tryBeginSignIn())
        gate.finishSignIn(); assertTrue(gate.tryBeginRequest())
    }
    @Test fun loginCannotRaceInferenceAndRefresh() {
        val gate = ChatGptSessionGate()
        assertTrue(gate.tryBeginRequest()); assertFalse(gate.tryBeginRequest())
        assertFalse(gate.tryBeginSignIn()); gate.finishRequest(); assertTrue(gate.tryBeginSignIn())
    }
    @Test fun logoutInvalidatesLateCallback() {
        val gate = ChatGptSessionGate(); assertTrue(gate.tryBeginSignIn())
        val old = gate.generation(); gate.invalidate(); assertNotEquals(old, gate.generation())
        assertFalse(gate.tryBeginRequest()); gate.finishSignIn(); assertTrue(gate.tryBeginRequest())
    }
    @Test fun concurrentRequestsHaveOneOwner() {
        val gate = ChatGptSessionGate(); val start = CountDownLatch(1); val winners = AtomicInteger()
        val threads = (1..20).map { Thread { start.await(); if (gate.tryBeginRequest()) winners.incrementAndGet() }.apply { start() } }
        start.countDown(); threads.forEach { it.join() }; assertEquals(1, winners.get())
    }
    @Test fun visibleModelSlugPreservesOrder() {
        val json = JSONObject("""{"models":[null,{"visibility":"hidden","slug":"hidden"},{"visibility":"list","slug":"gpt-6.1-sol"},{"visibility":"list","slug":"other"}]}""")
        assertEquals("gpt-6.1-sol", ChatGptCloudBridge.chooseModel(json))
    }
    @Test fun missingCatalogDoesNotInventModel() {
        val ex = assertThrows(ChatGptCloudBridge.CloudProblemError::class.java) { ChatGptCloudBridge.chooseModel(JSONObject("""{"data":[]} """)) }
        assertEquals(ChatGptCloudProblem.NO_MODELS_ARRAY, ex.problem)
    }
    @Test fun unavailableModelHasSpecificCode() {
        val ex = assertThrows(ChatGptCloudBridge.CloudProblemError::class.java) { ChatGptCloudBridge.chooseModel(JSONObject("""{"models":[{"visibility":"hidden","slug":"test"}]}""")) }
        assertEquals(ChatGptCloudProblem.NO_VISIBLE_MODEL, ex.problem)
    }
    @Test fun httpStatusCodeRequestIdPreserved() {
        val ex = ChatGptCloudBridge.classifiedHttpError(429, """{"error":{"code":"subscription_sharing_usage_limit_exceeded","message":"never reflect"}}""", "req_abcdef123")
        assertEquals(429, ex.httpCode); assertEquals("subscription_sharing_usage_limit_exceeded", ex.publicCode)
        assertEquals("req_abcdef123", ex.requestId); assertEquals("ERROR_OBJECT", ex.shape)
    }
    @Test fun arbitraryFieldsCannotLeakSecrets() {
        val secret = "Bearer secret-refresh-token"
        val ex = ChatGptCloudBridge.classifiedHttpError(401, JSONObject().put("error", JSONObject().put("code", secret).put("message", secret)).toString(), secret)
        assertNull(ex.publicCode); assertNull(ex.requestId); assertFalse(ex.toString().contains(secret))
    }
    @Test fun admissionDetailIsNotReflected() {
        val ex = ChatGptCloudBridge.classifiedHttpError(403, """{"detail":"private code state"}""", null)
        assertEquals("DETAIL", ex.shape); assertNull(ex.publicCode)
    }
    @Test fun oauthMachineCodePreserved() {
        val ex = ChatGptCloudBridge.classifiedHttpError(400, """{"error":"invalid_grant","error_description":"secret"}""", null)
        assertEquals("invalid_grant", ex.publicCode); assertEquals("OAUTH_ERROR", ex.shape)
    }
    @Test fun nonJsonErrorIsSafe() {
        val ex = ChatGptCloudBridge.classifiedHttpError(503, "<html>unavailable</html>", null)
        assertEquals("NON_JSON", ex.shape); assertNull(ex.publicCode)
    }
    @Test fun interruptedStreamHasSpecificProblem() {
        val ex = assertThrows(ChatGptCloudBridge.CloudProblemError::class.java) { ChatGptCloudBridge.readCompletedResponse("data: {\"type\":\"response.output_text.delta\",\"delta\":\"partial\"}\n\n".reader().buffered()) }
        assertEquals(ChatGptCloudProblem.INCOMPLETE_STREAM, ex.problem)
    }
    @Test fun streamedLimitErrorRetainsCode() {
        val ex = assertThrows(ChatGptCloudBridge.CloudProblemError::class.java) { ChatGptCloudBridge.readCompletedResponse("""data: {"type":"response.failed","response":{"error":{"code":"subscription_sharing_usage_limit_exceeded"}}}

""".reader().buffered()) }
        assertEquals(ChatGptCloudProblem.INFERENCE_FAILED, ex.problem); assertEquals("subscription_sharing_usage_limit_exceeded", ex.publicCode)
    }
}
