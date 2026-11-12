package com.example.airemote

import android.content.Context
import android.util.AtomicFile
import java.io.File

/** Durable inbox proof evidence BEFORE SQLite RECEIVED.
 * Never save a plaintext verified hand outside SQLite. */
class T043InboundPacketStore(context:Context) {
    private val directory=File(context.noBackupFilesDir,"t043_inbound/v1").also {
        require(it.isDirectory || it.mkdirs())
    }
    private fun file(sender:String,message:String):AtomicFile {
        require(sender.isNotBlank() && message.isNotBlank())
        return AtomicFile(File(directory,
            T043SyncProtocol.stableIdentityHash(sender,message)+".signed"))
    }
    @Synchronized fun save(header:T043SyncProtocol.Header,packet:ByteArray) {
        require(packet.size in 1..T043SyncProtocol.MAX_PACKET)
        require(T043SyncProtocol.decode(packet).header==header)
        val file=file(header.logicalSenderId,header.messageId)
        if(file.baseFile.exists()) {
            val prior=file.openRead().use { it.readBytes() }
            val old=T043SyncProtocol.decode(prior)
            // Another signed HPKE encapsulation may represent the same logical
            // content. Do not replace earlier proof evidence or silently merge
            // different envelopes under one stable message ID.
            require(old.header.originalEnvelopeSha256==header.originalEnvelopeSha256 &&
                old.header.logicalSenderId==header.logicalSenderId &&
                old.header.messageId==header.messageId) { "Inbound wire conflict" }
            return
        }
        val stream=file.startWrite()
        try {
            stream.write(packet)
            file.finishWrite(stream)
        } catch (e:Exception) {
            file.failWrite(stream)
            throw e
        }
    }
    @Synchronized fun read(sender:String,message:String):ByteArray? {
        val file=file(sender,message)
        if(!file.baseFile.exists())return null
        val bytes=file.openRead().use { it.readBytes() }
        require(bytes.size in 1..T043SyncProtocol.MAX_PACKET)
        val packet=T043SyncProtocol.decode(bytes)
        require(packet.header.logicalSenderId==sender &&
            packet.header.messageId==message)
        return bytes
    }
}
