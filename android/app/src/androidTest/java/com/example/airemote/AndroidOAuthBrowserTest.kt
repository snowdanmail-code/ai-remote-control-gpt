package com.example.airemote

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.BufferedReader
import java.io.StringReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLException

/** Emulator-only, synthetic credentials. No requests to real OAuth/token endpoints. */
@RunWith(AndroidJUnit4::class)
class AndroidOAuthBrowserTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun shell(command:String):String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    private fun activity():Activity {
        shell("input keyevent 82")
        shell("wm dismiss-keyguard")
        return instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    @Test fun browserOwnsCustomTabAndReturnsSyntheticLoopbackCallback() {
        val activity=activity()
        val state="fixture-state-android-123456789"
        val verifier="dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",ChatGptAuthProtocol.challenge(verifier))
        ServerSocket(0,1,InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout=180000
            val redirect="http://127.0.0.1:${server.localPort}/auth/callback"
            assertTrue(server.inetAddress.isLoopbackAddress)
            val received=AtomicReference<String>()
            val failure=AtomicReference<Throwable>()
            val worker=Thread {
                try { server.accept().use { socket ->
                    socket.soTimeout=3000
                    val line=socket.getInputStream().bufferedReader().readLine()
                    received.set(line.substringAfter("GET ").substringBefore(" HTTP/"))
                    val body="Synthetic callback received; no account was used.".toByteArray()
                    socket.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray());write(body);flush() }
                } } catch(t:Throwable) { failure.set(t) }
            }.apply { start() }
            instrumentation.runOnMainSync { ChatGptCloudBridge.launchSignInBrowser(activity,
                "$redirect?code=fixture-code-123&state=$state&client_id=fixture-client") }
            val deadline=android.os.SystemClock.elapsedRealtime()+170000
            while(received.get()==null && failure.get()==null && android.os.SystemClock.elapsedRealtime()<deadline) {
                fun dismiss(node:android.view.accessibility.AccessibilityNodeInfo?) {
                    if(node==null)return
                    val text=node.text?.toString().orEmpty()
                    if(text.lowercase(java.util.Locale.ROOT) in setOf("accept & continue","use without an account","use chrome without an account","continue without an account","no thanks","got it")) {
                        val action=if(node.isClickable) node else node.parent
                        action?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                    }
                    for(i in 0 until node.childCount)dismiss(node.getChild(i))
                }
                dismiss(instrumentation.uiAutomation.rootInActiveWindow)
                android.os.SystemClock.sleep(500)
            }
            worker.join(11000)
            failure.get()?.let {
                val labels=mutableListOf<String>()
                fun inspect(node:android.view.accessibility.AccessibilityNodeInfo?) {
                    if(node==null)return
                    if(node.className?.toString()?.contains("EditText")!=true) {
                        val text=node.text?.toString().orEmpty()
                        if(text.isNotBlank() && !text.contains("http",true) && !text.contains("@") && !text.contains("code=")) labels.add(text.take(100))
                    }
                    for(i in 0 until node.childCount)inspect(node.getChild(i))
                }
                inspect(instrumentation.uiAutomation.rootInActiveWindow)
                java.io.File(context.cacheDir,"test-browser-state.txt").writeText(labels.joinToString("\n"))
                throw AssertionError("Synthetic browser callback failed",it)
            }
            assertFalse("Callback listener still running",worker.isAlive)
            val callback=ChatGptAuthProtocol.validateCallback(requireNotNull(received.get()),redirect,state,null)
            assertEquals("fixture-code-123",callback.code)
            val foreground=shell("dumpsys activity activities").lineSequence().filter {
                it.contains("mResumedActivity") || it.contains("topResumedActivity") }.joinToString("\n")
            assertTrue("Expected browser-owned CustomTabActivity",foreground.contains("CustomTabActivity"))
            assertFalse("Page must not be app-owned",foreground.contains("com.example.airemote.dev/"))
        }
        shell("input keyevent 4")
        instrumentation.runOnMainSync { activity.finish() }
    }

    @Test fun malformedStateAndNonLoopbackCallbacksAreRejected() {
        assertFalse(ChatGptAuthProtocol.isLoopbackCallback("http://0.0.0.0:1455/auth/callback"))
        assertFalse(ChatGptAuthProtocol.isLoopbackCallback("https://example.org/auth/callback"))
        assertThrows(IllegalArgumentException::class.java) { ChatGptAuthProtocol.validateCallback(
            "/auth/callback?state=wrong&code=fixture-code-123&client_id=fixture-client",
            "http://127.0.0.1:1455/auth/callback","expected",null) }
    }

    @Test fun callbackTimeoutAndNetworkExceptionsRemainClassified() {
        ServerSocket(0,1,InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout=150
            val ex=assertThrows(SocketTimeoutException::class.java) { server.accept() }
            assertEquals(ChatGptCloudProblem.TIMEOUT,ChatGptTransportDiagnostics.problem(ex))
        }
        var attempts=0;var sent=0
        assertThrows(UnknownHostException::class.java) { ChatGptTokenConnect.openBeforeBody<Int>(
            create={++attempts},establish={throw UnknownHostException("synthetic")},discard={},pauseBeforeRetry={});sent++ }
        assertEquals(2,attempts);assertEquals(0,sent)
        attempts=0
        assertThrows(SSLException::class.java) { ChatGptTokenConnect.openBeforeBody<Int>(
            create={++attempts},establish={throw SSLException("synthetic")},discard={},pauseBeforeRetry={fail("TLS retry")}) }
        assertEquals(1,attempts)
        assertEquals(ChatGptCloudProblem.DNS_FAILURE,ChatGptTransportDiagnostics.problem(UnknownHostException()))
        assertEquals(ChatGptCloudProblem.TLS_FAILURE,ChatGptTransportDiagnostics.problem(SSLException("synthetic")))
    }

    @Test fun androidKeystoreEncryptsAndRotatesSyntheticRefreshRecord() {
        val prefs=context.getSharedPreferences("chatgpt_sign_in_private",Context.MODE_PRIVATE)
        assertFalse("Run only on a fresh isolated emulator",prefs.contains("sealed_credential"))
        val save=ChatGptCloudBridge::class.java.getDeclaredMethod("saveRecord",Context::class.java,JSONObject::class.java).apply { isAccessible=true }
        val read=ChatGptCloudBridge::class.java.getDeclaredMethod("readRecord",Context::class.java).apply { isAccessible=true }
        try {
            val record=JSONObject().put("client_id","oaiapp_fixture").put("sub","fixture-user")
                .put("refresh_token","synthetic-refresh-v1").put("access_token","synthetic-access")
            assertEquals(true,save.invoke(ChatGptCloudBridge,context,record))
            val first=requireNotNull(prefs.getString("sealed_credential",null));assertFalse(first.contains("synthetic-refresh"))
            record.put("refresh_token","synthetic-refresh-v2")
            assertEquals(true,save.invoke(ChatGptCloudBridge,context,record))
            assertNotEquals(first,prefs.getString("sealed_credential",null))
            assertEquals("synthetic-refresh-v2",(read.invoke(ChatGptCloudBridge,context) as JSONObject).getString("refresh_token"))
        } finally { prefs.edit().remove("sealed_credential").remove("sealed_registration").commit() }
    }

    @Test fun proofRequiresCompletedNonemptyResponseOnAndroid() {
        val partial="data: {\"type\":\"response.output_text.delta\",\"delta\":\"fixture\"}\n\n"
        assertThrows(Exception::class.java) { ChatGptCloudBridge.readCompletedResponse(BufferedReader(StringReader(partial))) }
        val completed=partial+"data: {\"type\":\"response.completed\"}\n\n"
        assertEquals("fixture",ChatGptCloudBridge.readCompletedResponse(BufferedReader(StringReader(completed))))
        assertThrows(Exception::class.java) { ChatGptCloudBridge.readCompletedResponse(BufferedReader(StringReader("data: {\"type\":\"response.completed\"}\n\n"))) }
    }
}
