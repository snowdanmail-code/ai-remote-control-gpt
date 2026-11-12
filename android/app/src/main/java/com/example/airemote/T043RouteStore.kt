package com.example.airemote

import android.content.Context
import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.charset.StandardCharsets

/** App-private, crash-safe route + CIPHERTEXT journal. No private keys/passwords.
 * The protected SQL logical recipient and original envelope are never edited.
 * An existing route wins over new preferences; conflicts FAIL, never silently reroute. */
class T043RouteStore(context: Context) {
    enum class Status { PREPARED, PUBLISHED }
    data class Snapshot(val packet: ByteArray, val header: T043SyncProtocol.Header,
                        val state: Status, val packetSha256: String)

    private val directory = File(context.noBackupFilesDir,"t043_sync_routes/v1").also {
        require(it.isDirectory || it.mkdirs()) { "Cannot initialize local route journal" }
    }
    private val magic = 0x54335231 // T3R1

    private fun file(sender: String,message: String): AtomicFile {
        require(sender.isNotBlank() && message.isNotBlank())
        val bound = sender.toByteArray(StandardCharsets.UTF_8) +
            byteArrayOf(0) + message.toByteArray(StandardCharsets.UTF_8)
        return AtomicFile(File(directory,T043MessageFingerprint.sha256(bound)+".route"))
    }
    private fun encode(snapshot: Snapshot): ByteArray {
        val bytes=ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(magic);out.writeInt(1);out.writeInt(snapshot.state.ordinal)
            val hash=T043MessageFingerprint.sha256(snapshot.packet)
            require(snapshot.packetSha256==hash)
            out.writeInt(snapshot.packet.size);out.write(snapshot.packet)
        }
        return bytes.toByteArray()
    }
    private fun decode(raw: ByteArray): Snapshot {
        require(raw.size<=T043SyncProtocol.MAX_PACKET+64)
        val packet: ByteArray
        val state: Status
        DataInputStream(ByteArrayInputStream(raw)).use { input ->
            require(input.readInt()==magic && input.readInt()==1)
            state=Status.values()[input.readInt()]
            val length=input.readInt()
            require(length in 1..T043SyncProtocol.MAX_PACKET &&
                length<=input.available())
            packet=ByteArray(length);input.readFully(packet)
            require(input.available()==0)
        }
        val parsed=T043SyncProtocol.decode(packet)
        return Snapshot(packet,parsed.header,state,T043MessageFingerprint.sha256(packet))
    }

    @Synchronized fun read(sender: String,message: String): Snapshot? {
        val f=file(sender,message)
        if(!f.baseFile.exists())return null
        val value=decode(f.openRead().use { it.readBytes() })
        require(value.header.logicalSenderId==sender && value.header.messageId==message) {
            "Route journal identity mismatch"
        }
        return value
    }

    private fun store(sender: String,message: String,snapshot: Snapshot) {
        val target=file(sender,message)
        val stream=target.startWrite()
        try {
            stream.write(encode(snapshot))
            target.finishWrite(stream)
        } catch (e: Exception) {
            target.failWrite(stream)
            throw e
        }
    }

    @Synchronized fun prepare(
        header: T043SyncProtocol.Header,
        preparedPacket: ByteArray
    ): Snapshot {
        val existing=read(header.logicalSenderId,header.messageId)
        if(existing != null) {
            // No recipient/key/representation changes on retry (or reboot).
            require(existing.header==header && existing.packet.contentEquals(preparedPacket)) {
                "A route/packet already committed for this message"
            }
            return existing
        }
        val parsed=T043SyncProtocol.decode(preparedPacket)
        require(parsed.header==header)
        val item=Snapshot(preparedPacket.copyOf(),header,Status.PREPARED,
            T043MessageFingerprint.sha256(preparedPacket))
        store(header.logicalSenderId,header.messageId,item)
        return item
    }

    /** Read BEFORE calling an encryptor; never re-encrypt an already prepared message. */
    @Synchronized fun recover(sender: String,message: String,
                              originalEnvelopeSha256: String): Snapshot? {
        val snapshot=read(sender,message) ?: return null
        require(snapshot.header.originalEnvelopeSha256==originalEnvelopeSha256) {
            "Outbox changed under persistent route"
        }
        return snapshot
    }

    @Synchronized fun published(sender:String,message:String): Snapshot {
        val old=checkNotNull(read(sender,message)) { "No pending route" }
        if(old.state==Status.PUBLISHED)return old
        return old.copy(state=Status.PUBLISHED).also { store(sender,message,it) }
    }
}
