package com.example.airemote

import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.ssl.SslConfigurationFactory
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.util.UUID

/** Actual loopback-only FTPS handshake tests. No remote server, installed keys
 * or hardcoded credentials. A fresh temporary self-signed cert is destroyed
 * after the test and its randomly generated password is never logged. */
class T043FtpsSecurityJvmTest {
    private fun port():Int=ServerSocket(0).use { it.localPort }
    private fun start(port:Int,keystore:File?,password:String?):FtpServer {
        val factory=FtpServerFactory()
        val listener=ListenerFactory()
        listener.port=port
        if(keystore!=null && password!=null) {
            val ssl=SslConfigurationFactory()
            ssl.setKeystoreFile(keystore)
            ssl.setKeystoreType("PKCS12")
            ssl.setKeystorePassword(password)
            ssl.setKeyPassword(password)
            ssl.setSslProtocol("TLSv1.2")
            listener.setSslConfiguration(ssl.createSslConfiguration())
            listener.setImplicitSsl(false)
        }
        factory.addListener("default",listener.createListener())
        return factory.createServer().also { it.start() }
    }
    private fun generateKeystore(file:File,password:String) {
        val executable=File(System.getProperty("java.home"),"bin/keytool").absolutePath
        val process=ProcessBuilder(executable,
            "-genkeypair","-alias","fixture-ftps","-keyalg","RSA",
            "-keysize","2048","-validity","1","-storetype","PKCS12",
            "-keystore",file.absolutePath,
            "-storepass",password,"-keypass",password,
            "-dname","CN=localhost","-ext","SAN=dns:localhost",
            "-noprompt").redirectErrorStream(true).start()
        process.inputStream.use { it.readBytes() }
        check(process.waitFor()==0) { "Ephemeral test certificate generation failed" }
    }
    private fun requireRejected(port:Int) {
        val transport=T043FtpsTransport(
            T043FtpsEndpoint("localhost",port,approved=true,timeoutMs=3000),
            T043FtpsCredentials { _ -> fail("FTP credentials must not be sent") }
        )
        var rejected=false
        try { transport.list("test-recipient","stream") }
        catch (_:Exception) { rejected=true }
        assertTrue("Certificate or downgrade bypassed",rejected)
    }
    @Test fun t3_35a_untrusted_self_signed_ftps_certificate_rejected() {
        val dir=Files.createTempDirectory("t043-ftps-selfsigned").toFile()
        val key=File(dir,"untrusted.p12")
        val secret=UUID.randomUUID().toString().replace("-","")
        try {
            generateKeystore(key,secret)
            val selectedPort=port()
            val server=start(selectedPort,key,secret)
            try { requireRejected(selectedPort) }
            finally { server.stop() }
        } finally { dir.deleteRecursively() }
    }
    @Test fun t3_35b_plain_ftp_downgrade_fails_closed() {
        val selectedPort=port()
        val server=start(selectedPort,null,null)
        try { requireRejected(selectedPort) }
        finally { server.stop() }
    }
}
