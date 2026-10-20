package com.example.airemote

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Synthetic emulator-only storage checks; isolated prefs, no user tokens or key deletion. */
@RunWith(AndroidJUnit4::class)
class AndroidOAuth042StorageTest {
    private lateinit var context: Context
    @Before fun isolate() {
        check(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("Emulator")) { "Emulator-only fixture" }
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        context=object:ContextWrapper(target) {
            override fun getSharedPreferences(name:String,mode:Int):SharedPreferences = super.getSharedPreferences(name+"_oauth042_fixture",mode)
        }
        check(context.getSharedPreferences("chatgpt_sign_in_private",0).edit().clear().commit())
        val fault=ChatGptCloudBridge::class.java.getDeclaredField("storageFault").apply { isAccessible=true }
        fault.setBoolean(ChatGptCloudBridge,false)
    }
    private fun record(refresh:String)=JSONObject().put("client_id","oaiapp_fixture").put("sub","fixture-user")
        .put("access_token","fixture-access").put("refresh_token",refresh).put("scope",ChatGptAuthProtocol.SCOPE).put("expires_at_ms",System.currentTimeMillis()+3600000)
    private fun save(r:JSONObject):Boolean=ChatGptCloudBridge::class.java.getDeclaredMethod("saveRecord",Context::class.java,JSONObject::class.java).apply { isAccessible=true }.invoke(ChatGptCloudBridge,context,r) as Boolean
    private fun read():JSONObject?=ChatGptCloudBridge::class.java.getDeclaredMethod("readRecord",Context::class.java).apply { isAccessible=true }.invoke(ChatGptCloudBridge,context) as JSONObject?
    @Test fun keystoreRotationPersistsOnlySealedCredentials() {
        assertTrue(save(record("fixture-refresh-1")));assertTrue(save(record("fixture-refresh-2")))
        assertEquals("fixture-refresh-2",read()!!.getString("refresh_token"))
        val prefs=context.getSharedPreferences("chatgpt_sign_in_private",0)
        assertFalse(prefs.all.values.toString().contains("fixture-refresh"))
        assertFalse(prefs.all.values.toString().contains("fixture-user"))
    }
    @Test fun corruptKeystoreRecordIsBadStorageNotNoSession() {
        assertTrue(context.getSharedPreferences("chatgpt_sign_in_private",0).edit().putString("sealed_credential","invalid-ciphertext").commit())
        val e=assertThrows(InvocationTargetException::class.java) { read() }
        assertEquals(ChatGptCloudProblem.BAD_STORAGE,(e.cause as ChatGptCloudBridge.CloudProblemError).problem)
    }
    @Test fun logoutRetainsVerifiedIdentityMappingAndClearsSession() {
        assertTrue(save(record("")));val done=CountDownLatch(1)
        ChatGptCloudBridge.signOut(context) { done.countDown() }
        assertTrue(done.await(5,TimeUnit.SECONDS));assertNull(read())
        val registration=ChatGptCloudBridge::class.java.getDeclaredMethod("readProtected",Context::class.java,String::class.java).apply { isAccessible=true }.invoke(ChatGptCloudBridge,context,"sealed_registration") as JSONObject
        assertEquals("fixture-user",registration.getString("sub"));assertEquals("oaiapp_fixture",registration.getString("client_id"))
        assertFalse(registration.has("access_token"));assertFalse(registration.has("refresh_token"))
    }
    @Test fun pendingRefreshReservationSurvivesStoreRestore() {
        assertTrue(save(record("fixture-refresh").put("refresh_pending",true)))
        assertTrue(ChatGptTokenResponsePolicy.requiresReauthorization(read()!!.getBoolean("refresh_pending")))
    }
}
