package com.example.airemote

import java.io.File
import java.net.InetAddress
import java.net.Proxy
import java.net.URL
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.*

/** Isolated self-signed HTTPS fixture. Its temporary key never enters source control or APK. */
internal object TlsOAuthFixture {
    private val directory=Files.createTempDirectory("airemote-test-tls-").toFile()
    private val store=File(directory,"fixture.p12")
    val serverContext: SSLContext
    val clientContext: SSLContext
    init {
        val keytool=File(System.getProperty("java.home"),"bin/keytool").absolutePath
        val command=listOf(keytool,"-genkeypair","-alias","fixture","-keyalg","RSA","-keysize","2048","-storetype","PKCS12","-keystore",store.absolutePath,"-storepass","fixture-only","-keypass","fixture-only","-dname","CN=localhost","-ext","SAN=dns:localhost,ip:127.0.0.1","-validity","2","-noprompt")
        val process=ProcessBuilder(command).redirectErrorStream(true).redirectOutput(File(directory,"keytool.log")).start()
        check(process.waitFor(30,TimeUnit.SECONDS) && process.exitValue()==0) { "TLS fixture generation failed" }
        val keys=KeyStore.getInstance("PKCS12").apply { store.inputStream().use { load(it,"fixture-only".toCharArray()) } }
        val km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys,"fixture-only".toCharArray()) }
        serverContext=SSLContext.getInstance("TLS").apply { init(km.keyManagers,null,null) }
        val tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }
        clientContext=SSLContext.getInstance("TLS").apply { init(null,tm.trustManagers,null) }
    }
    fun server():SSLServerSocket = (serverContext.serverSocketFactory.createServerSocket(0,16,InetAddress.getByName("127.0.0.1")) as SSLServerSocket)
    fun connection(port:Int,path:String,body:ByteArray?=null,trustFixture:Boolean=true):HttpsURLConnection =
        (URL("https://127.0.0.1:$port$path").openConnection(Proxy.NO_PROXY) as HttpsURLConnection).apply {
            if(trustFixture) sslSocketFactory=clientContext.socketFactory
            instanceFollowRedirects=false; connectTimeout=2000;readTimeout=2000
            requestMethod=if(body==null) "GET" else "POST"
            if(body!=null) { doOutput=true;setFixedLengthStreamingMode(body.size);setRequestProperty("Content-Type","application/x-www-form-urlencoded") }
        }
    fun cleanup() { directory.deleteRecursively() }
}

/** Reads actual TLS HTTP requests, counting complete bodies before replying or closing. */
internal class TlsOAuthServer(private val responseStatus:Int=200,private val closeAfterBody:Boolean=false):AutoCloseable {
    private val server=TlsOAuthFixture.server().apply { soTimeout=250 }
    val port get()=server.localPort
    val receivedBodies=AtomicInteger()
    private val worker=Thread {
        while(!server.isClosed) {
            try { server.accept().use { socket ->
                socket.soTimeout=2000
                val reader=socket.getInputStream().bufferedReader()
                reader.readLine() ?: return@use
                var size=0
                while(true) { val line=reader.readLine() ?: break;if(line.isEmpty()) break
                    if(line.startsWith("Content-Length:",true)) size=line.substringAfter(':').trim().toInt()
                }
                val buffer=CharArray(size);var count=0
                while(count<size) { val n=reader.read(buffer,count,size-count);if(n<0) return@use;count+=n }
                receivedBodies.incrementAndGet()
                if(closeAfterBody) return@use
                val bytes="{\"fixture\":true}".toByteArray()
                socket.getOutputStream().apply { write("HTTP/1.1 $responseStatus Fixture\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray());write(bytes);flush() }
            } } catch (_:SocketTimeoutException) {} catch (_:javax.net.ssl.SSLException) {} catch (ex:java.io.IOException) { if(!server.isClosed) throw ex }
        }
    }.apply { isDaemon=true;start() }
    override fun close() { server.close();worker.join(3000) }
}
