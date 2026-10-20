package com.example.airemote

import org.junit.Assert.*
import org.junit.Test
import java.net.UnknownHostException

class OAuth042RegressionTest {
    @Test fun dnsDuringConnectionCreationRetriesBeforeAnyBody() {
        var attempts=0
        val c=ChatGptTokenConnect.openBeforeBody(create={ if(++attempts==1) throw UnknownHostException("secret-host");attempts }, establish={}, discard={}, pauseBeforeRetry={})
        assertEquals(2,c);assertEquals(2,attempts)
    }
    @Test fun emptyErrorCallbackCannotBeAcceptedWithCode() {
        val s="s".repeat(32)
        assertThrows(IllegalArgumentException::class.java) {
            ChatGptAuthProtocol.validateCallback("/auth/callback?state=$s&code=synthetic-code&client_id=oaiapp_fixture&error=", "http://127.0.0.1:1455/auth/callback",s,null)
        }
    }
    @Test fun nonRfcPkceRejected() {
        for(v in listOf("v".repeat(32),"v".repeat(129),"!".repeat(48))) {
            assertThrows(IllegalArgumentException::class.java) { ChatGptAuthProtocol.authorizationUrl("urn:uuid:fixture","http://127.0.0.1:1455/auth/callback","s".repeat(32),"n".repeat(32),v) }
        }
    }
}
