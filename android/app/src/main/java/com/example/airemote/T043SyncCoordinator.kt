package com.example.airemote

/** Explicit enrollment approval; never derived from an FTP filename or upload. */
data class T043RouteApproval(
    val senderDeviceId:String,
    val recipientDeviceId:String
)

/** Isolated Stage3 worker. Does not start itself, modify gameplay or create an FTP
 * connection unless publish() is explicitly invoked with approved transport.
 * Recovery restores EXACT committed ciphertext and previously pinned recipient.
 */
class T043SyncCoordinator(
    private val repository:T043SyncSqlGateway,
    private val routes:T043RouteStore,
    private val registry:T043DeviceIdentityRegistry,
    private val fileTransport:T043FileTransport
) {
    @Synchronized fun prepare(
        sender:String,message:String,approval:T043RouteApproval,
        signer:T043SigningKey
    ):T043RouteStore.Snapshot {
        val row=requireNotNull(repository.readOutbox(sender,message)) {
            "No durable outbound message"
        }
        require(row.deliveryState in setOf("PENDING","RETRY","LEASED","SENT_UNCONFIRMED"))
        val src=registry.requireReceiptSignerForRoute(approval.senderDeviceId)
        val dst=registry.requireEncryptionRecipientForRoute(approval.recipientDeviceId)
        require(sender in src.allowedLogicalSenders) { "Game namespace not delegated to device" }
        val old=routes.recover(sender,message,row.envelopeSha256)
        if(old!=null) {
            require(old.header.senderDeviceId==approval.senderDeviceId &&
                old.header.recipientDeviceId==approval.recipientDeviceId &&
                old.header.senderSigningKeyId==src.signingKeyId &&
                old.header.senderKeyVersion==src.signingVersion &&
                old.header.recipientEncryptionKeyId==dst.encryptionKeyId &&
                old.header.recipientKeyVersion==dst.encryptionVersion) {
                "Previously committed route cannot be changed silently"
            }
            val packet=T043SyncProtocol.decode(old.packet)
            val input=T043SyncProtocol.signingInput(
                T043SyncProtocol.headerBytes(packet.header),packet.enc,packet.ciphertext)
            require(T043SyncCrypto.verifyEd25519(src.signingPublicKey,input,packet.signature))
            return old
        }
        val e=row.envelope
        val h=T043SyncProtocol.Header(
            senderDeviceId=approval.senderDeviceId,
            senderSigningKeyId=src.signingKeyId,
            senderKeyVersion=src.signingVersion,
            recipientDeviceId=approval.recipientDeviceId,
            recipientEncryptionKeyId=dst.encryptionKeyId,
            recipientKeyVersion=dst.encryptionVersion,
            logicalSenderId=e.senderId,
            logicalRecipientId=e.recipientId,
            streamId=e.streamId,
            messageId=e.messageId,
            sequenceNo=e.sequenceNo,
            contentType=e.contentType,
            originalEnvelopeSha256=row.envelopeSha256,
            originalRawSha256=row.rawSha256,
            sourceNamespace=e.sourceNamespace,
            relatedRecordType=e.relatedRecordType,
            relatedRecordKey=e.relatedRecordKey,
            rulesetId=e.rulesetId,
            payloadEncoding=e.payloadEncoding,
            schemaMajor=e.schemaMajor,schemaMinor=e.schemaMinor,
            canonicalizationId=e.canonicalizationId
        )
        val packet=T043SyncCrypto.seal(h,row.bytes,dst.encryptionPublicKey,signer)
        val parsed=T043SyncProtocol.decode(packet)
        require(T043SyncCrypto.verifyEd25519(
            src.signingPublicKey,
            T043SyncProtocol.signingInput(T043SyncProtocol.headerBytes(h),
                parsed.enc,parsed.ciphertext),
            parsed.signature)) { "Signer is not authorized for the pinned sender device" }
        return routes.prepare(h,packet)
    }

    @Synchronized fun publish(sender:String,message:String,
                              approval:T043RouteApproval,
                              signer:T043SigningKey) {
        val prepared=prepare(sender,message,approval,signer)
        // File transport may fail: the prepared packet is already durable.
        // Next invocation reuses it; never reencrypts or silently changes device.
        fileTransport.publish(prepared.header,prepared.packet)
        routes.published(sender,message)
        val row=requireNotNull(repository.readOutbox(sender,message))
        require(row.envelopeSha256==prepared.header.originalEnvelopeSha256)
        repository.markPublished(row)
    }
}

/** A real signed HPKE packet must be validated BEFORE anything reaches SQLite.
 * Receipt contents do not prove verified game import; this only records RECEIVED.
 */
class T043SyncInboxTransport(
    private val registry:T043DeviceIdentityRegistry,
    private val deviceId:String,
    private val privateKey:T043HpkeSecret,
    private val durablePackets:T043InboundPacketStore,
    private val canonical:T043CanonicalSqliteStore
) {
    @Synchronized fun receiveAuthenticatedPacket(packet:ByteArray):T043WriteResult {
        return try {
            val (header,original)=T043SyncCrypto.open(packet,registry,deviceId,privateKey)
            durablePackets.save(header,packet)
            canonical.receive(header.originalEnvelope(),original)
        } catch (_:Exception) { T043WriteResult.REJECTED }
    }
    /** Reauthenticate even if the packet is already present on disk after a crash. */
    fun reloadAuthenticated(sender:String,message:String):Pair<T043SyncProtocol.Header,ByteArray>? {
        val packet=durablePackets.read(sender,message) ?: return null
        return T043SyncCrypto.open(packet,registry,deviceId,privateKey)
    }
}
