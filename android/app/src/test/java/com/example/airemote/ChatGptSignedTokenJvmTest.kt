package com.example.airemote
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64

internal object SignedTokenFixture {
    val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private fun encode(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    fun keys(): JSONArray {
        val key = pair.public as RSAPublicKey
        fun unsigned(b: ByteArray) = if (b[0] == 0.toByte()) b.copyOfRange(1, b.size) else b
        return JSONArray().put(JSONObject().put("kty", "RSA").put("kid", "fixture").put("use", "sig").put("alg", "RS256")
            .put("n", encode(unsigned(key.modulus.toByteArray()))).put("e", encode(unsigned(key.publicExponent.toByteArray()))))
    }
    fun claims(now: Long = System.currentTimeMillis()/1000): JSONObject = JSONObject().put("iss", ChatGptAuthProtocol.ISSUER)
        .put("aud", "oaiapp_fixture").put("sub", "fixture-user").put("nonce", "n".repeat(32)).put("iat", now).put("exp", now+3600)
    fun sign(claims: JSONObject = claims()): String {
        val input = encode(JSONObject().put("alg","RS256").put("kid","fixture").toString().toByteArray())+"."+encode(claims.toString().toByteArray())
        val signer = Signature.getInstance("SHA256withRSA").apply { initSign(pair.private); update(input.toByteArray(Charsets.US_ASCII)) }
        return input+"."+encode(signer.sign())
    }
}
class ChatGptSignedTokenJvmTest {
    private val now=1800000000L
    private fun verify(c:JSONObject=SignedTokenFixture.claims(now), keys:JSONArray=SignedTokenFixture.keys(), nonce:String="n".repeat(32)) = ChatGptIdTokenVerifier.verify(SignedTokenFixture.sign(c),"oaiapp_fixture",nonce,keys,now)
    @Test fun validSignedIdentity() { assertEquals("fixture-user",verify().getString("sub")) }
    @Test fun wrongNonce() { assertThrows(IllegalArgumentException::class.java) { verify(nonce="wrong") } }
    @Test fun wrongAudience() { assertThrows(IllegalArgumentException::class.java) { verify(SignedTokenFixture.claims(now).put("aud","other")) } }
    @Test fun wrongIssuer() { assertThrows(IllegalArgumentException::class.java) { verify(SignedTokenFixture.claims(now).put("iss","https://invalid.example")) } }
    @Test fun expired() { assertThrows(IllegalArgumentException::class.java) { verify(SignedTokenFixture.claims(now).put("exp",now)) } }
    @Test fun notYetValid() { assertThrows(IllegalArgumentException::class.java) { verify(SignedTokenFixture.claims(now).put("nbf",now+61)) } }
    @Test fun futureIssuedAt() { assertThrows(IllegalArgumentException::class.java) { verify(SignedTokenFixture.claims(now).put("iat",now+120)) } }
    @Test fun ambiguousKey() { val keys=SignedTokenFixture.keys();keys.put(keys.getJSONObject(0));assertThrows(IllegalArgumentException::class.java) { verify(keys=keys) } }
    @Test fun multipleAudiencesRequireAzp() { assertThrows(IllegalArgumentException::class.java) { verify(SignedTokenFixture.claims(now).put("aud",JSONArray().put("other").put("oaiapp_fixture"))) } }
    @Test fun tamperedSignature() {
        val parts=SignedTokenFixture.sign(SignedTokenFixture.claims(now)).split('.').toMutableList()
        parts[1]=Base64.getUrlEncoder().withoutPadding().encodeToString(SignedTokenFixture.claims(now).put("sub","tampered").toString().toByteArray())
        assertThrows(IllegalArgumentException::class.java) { ChatGptIdTokenVerifier.verify(parts.joinToString("."),"oaiapp_fixture","n".repeat(32),SignedTokenFixture.keys(),now) }
    }
}
