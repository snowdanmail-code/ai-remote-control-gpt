package com.example.airemote
import org.junit.Assert.*
import org.junit.Test
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class ChatGptIdentityNetworkJvmTest {
    @Test fun jwksDnsFailureRemainsDnsFailure() {
        val failure=UnknownHostException("private details")
        val caught=assertThrows(UnknownHostException::class.java) {
            ChatGptIdTokenVerifier.verifyWithKeys(SignedTokenFixture.sign(),"oaiapp_fixture","n".repeat(32)) { throw failure }
        }
        assertSame(failure,caught)
        val diagnostic=ChatGptCloudBridge.describe(caught,ChatGptCloudStep.IDENTITY)
        assertTrue(diagnostic.contains("AUTH_IDENTITY_DNS_FAILURE"));assertFalse(diagnostic.contains("private details"));assertFalse(diagnostic.contains("BAD_IDENTITY"))
    }
    @Test fun jwksTlsFailureRemainsTlsFailure() {
        val failure=SSLHandshakeException("private details")
        val caught=assertThrows(SSLHandshakeException::class.java) {
            ChatGptIdTokenVerifier.verifyWithKeys(SignedTokenFixture.sign(),"oaiapp_fixture","n".repeat(32)) { throw failure }
        }
        assertSame(failure,caught)
        assertTrue(ChatGptCloudBridge.describe(caught,ChatGptCloudStep.IDENTITY).contains("AUTH_IDENTITY_TLS_FAILURE"))
    }
    @Test fun jwksHttpFailureRemainsHttpFailure() {
        val failure=ChatGptCloudBridge.CloudHttpError(503)
        val caught=assertThrows(ChatGptCloudBridge.CloudHttpError::class.java) {
            ChatGptIdTokenVerifier.verifyWithKeys(SignedTokenFixture.sign(),"oaiapp_fixture","n".repeat(32)) { throw failure }
        }
        assertSame(failure,caught)
        assertTrue(ChatGptCloudBridge.describe(caught,ChatGptCloudStep.IDENTITY).contains("AUTH_IDENTITY_HTTP_503"))
    }
    @Test fun invalidNonceGetsSpecificIdentityCode() {
        val ex=assertThrows(ChatGptIdTokenVerifier.Rejection::class.java) {
            ChatGptIdTokenVerifier.verify(SignedTokenFixture.sign(),"oaiapp_fixture","wrong",SignedTokenFixture.keys())
        }
        assertEquals(ChatGptCloudProblem.INVALID_NONCE,ex.problem)
        assertTrue(ChatGptCloudBridge.describe(ex,ChatGptCloudStep.IDENTITY).contains("AUTH_IDENTITY_INVALID_NONCE"))
    }
    @Test fun missingTypStillRequiresValidSignature() {
        assertEquals("fixture-user",ChatGptIdTokenVerifier.verify(SignedTokenFixture.sign(),"oaiapp_fixture","n".repeat(32),SignedTokenFixture.keys()).getString("sub"))
    }
    @Test fun invalidJwksShapeIsNotReportedAsDns() {
        val ex=assertThrows(ChatGptIdTokenVerifier.Rejection::class.java) {
            ChatGptIdTokenVerifier.verifyWithKeys(SignedTokenFixture.sign(),"oaiapp_fixture","n".repeat(32)) { throw org.json.JSONException("private json") }
        }
        assertEquals(ChatGptCloudProblem.INVALID_JWKS_RESPONSE,ex.problem)
    }
}
