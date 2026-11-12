package com.example.airemote

import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.time.Duration

/** Explicit, synchronous file transport; NEVER call from the UI thread. */
interface T043FileTransport {
    fun publish(header:T043SyncProtocol.Header,packet:ByteArray)
    fun list(recipientDeviceId:String,streamId:String):List<String>
    fun fetch(path:String):ByteArray
}

/** External host must be explicitly approved; no FTP fallback. */
data class T043FtpsEndpoint(
    val hostname:String,
    val port:Int=21,
    val approved:Boolean=false,
    val timeoutMs:Int=15_000
) {
    init {
        require(Regex("[A-Za-z0-9.-]{1,253}").matches(hostname) &&
            !hostname.startsWith(".") && !hostname.endsWith(".") &&
            !hostname.contains("..") && hostname.any { it.isLetter() })
        require(port in 1..65535 && timeoutMs in 1000..60000)
    }
}
fun interface T043FtpsCredentials {
    fun useCredentials(operation:(String,String)->Unit)
}

/** Apache Commons Net FTPS 3.13. Control TLS, endpoint name validation,
 * and PBSZ=0/PROT=P data TLS are MANDATORY. Server not an ACK authority. */
class T043FtpsTransport(private val endpoint:T043FtpsEndpoint,
                         private val credentials:T043FtpsCredentials):T043FileTransport {
    private val segment=Regex("[A-Za-z0-9_-]{1,128}")
    private fun <T> withClient(block:(FTPSClient)->T):T {
        check(endpoint.approved) { "FTPS configuration not approved" }
        val ftp=FTPSClient("TLS",false)
        ftp.setEndpointCheckingEnabled(true)
        ftp.setEnabledProtocols(arrayOf("TLSv1.3","TLSv1.2"))
        ftp.connectTimeout=endpoint.timeoutMs
        ftp.defaultTimeout=endpoint.timeoutMs
        ftp.setDataTimeout(Duration.ofMillis(endpoint.timeoutMs.toLong()))
        try {
            ftp.connect(endpoint.hostname,endpoint.port)
            check(FTPReply.isPositiveCompletion(ftp.replyCode))
            credentials.useCredentials { user,pass ->
                require(user.isNotBlank() && pass.isNotBlank())
                check(ftp.login(user,pass)) { "FTPS authentication refused" }
            }
            ftp.execPBSZ(0)
            ftp.execPROT("P")
            ftp.enterLocalPassiveMode()
            check(ftp.setFileType(FTP.BINARY_FILE_TYPE))
            return block(ftp)
        } finally {
            try { if(ftp.isConnected) ftp.logout() } catch (_: Exception) {}
            try { if(ftp.isConnected) ftp.disconnect() } catch (_: Exception) {}
        }
    }
    private fun safeDirectory(path:String):String {
        val parts=path.trim('/').split("/")
        require(parts.size==5 && parts[0]=="t043" && parts[1]=="v1" &&
            parts[2]=="inbox" && parts.all { segment.matches(it) })
        return "/"+parts.joinToString("/")
    }
    private fun createDirectories(ftp:FTPSClient,path:String) {
        var cumulative=""
        for(part in path.trim('/').split("/")) {
            cumulative+="/"+part
            if(!ftp.changeWorkingDirectory(cumulative)) {
                check(ftp.makeDirectory(cumulative) || ftp.changeWorkingDirectory(cumulative))
            }
        }
    }
    private fun readBounded(ftp:FTPSClient,path:String):ByteArray {
        val buffer=ByteArrayOutputStream()
        val limited=object:OutputStream() {
            override fun write(b:Int) {
                check(buffer.size()<T043SyncProtocol.MAX_PACKET)
                buffer.write(b)
            }
            override fun write(b:ByteArray,off:Int,len:Int) {
                check(buffer.size().toLong()+len<=T043SyncProtocol.MAX_PACKET)
                buffer.write(b,off,len)
            }
        }
        check(ftp.retrieveFile(path,limited)) { "FTPS retrieval failed" }
        return buffer.toByteArray()
    }
    override fun publish(header:T043SyncProtocol.Header,packet:ByteArray) {
        require(packet.size<=T043SyncProtocol.MAX_PACKET)
        require(T043SyncProtocol.decode(packet).header==header)
        val dir=safeDirectory(T043SyncProtocol.mailboxPath(header))
        val name=T043SyncProtocol.fileName(header)
        withClient { ftp ->
            createDirectories(ftp,dir)
            val finalPath="$dir/$name"
            if(ftp.listFiles(finalPath).any { it.name==name }) {
                check(readBounded(ftp,finalPath).contentEquals(packet)) {
                    "Remote file name already contains different ciphertext"
                }
                return@withClient
            }
            val temp="$dir/." + java.util.UUID.randomUUID().toString().replace("-","") + ".tmp"
            check(ftp.storeFile(temp,ByteArrayInputStream(packet))) { "FTPS temp upload failed" }
            check(readBounded(ftp,temp).contentEquals(packet)) { "FTPS temp file damaged" }
            check(ftp.rename(temp,finalPath)) { "FTPS final rename failed" }
        }
    }
    override fun list(recipientDeviceId:String,streamId:String):List<String> {
        require(segment.matches(recipientDeviceId))
        require(streamId.isNotBlank() && streamId.length<=128)
        val dir=safeDirectory("/t043/v1/inbox/"+recipientDeviceId+"/"+
            T043MessageFingerprint.sha256(streamId.toByteArray()).take(32))
        return withClient { ftp ->
            ftp.listFiles(dir).mapNotNull { it ->
                if(it.name.matches(Regex("m-[0-9a-f]{64}-[0-9]{20}\\.t043")) &&
                    it.size in 1..T043SyncProtocol.MAX_PACKET.toLong())
                    dir+"/"+it.name else null
            }.sorted().take(1000)
        }
    }
    override fun fetch(path:String):ByteArray {
        val index=path.lastIndexOf('/')
        require(index>0)
        val dir=safeDirectory(path.substring(0,index))
        val name=path.substring(index+1)
        require(name.matches(Regex("m-[0-9a-f]{64}-[0-9]{20}\\.t043")))
        return withClient { ftp -> readBounded(ftp,dir+"/"+name) }
    }
}
