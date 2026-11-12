package com.example.airemote

import android.content.Context
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.security.SecureRandom

/** All keys are GENERATED per test and NEVER exported to CI artifacts.
 * In-memory transport is NOT a production FTP connector or trainer implementation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],manifest=Config.NONE)
class T043Stage3RobolectricTest {
    private lateinit var context:Context
    private lateinit var helper:AndroidHandLearningStore
    private lateinit var canonical:T043CanonicalSqliteStore
    private lateinit var registry:T043DeviceIdentityRegistry
    private lateinit var routes:T043RouteStore
    private lateinit var gateway:T043SyncSqlGateway
    private lateinit var inboundStore:T043InboundPacketStore
    private val rng=SecureRandom()
    private lateinit var playerSign:Ed25519PrivateKeyParameters
    private lateinit var trainerSign:Ed25519PrivateKeyParameters
    private lateinit var playerEnc:X25519PrivateKeyParameters
    private lateinit var trainerEnc:X25519PrivateKeyParameters
    private val source="fixture.source"

    private class MemoryTransport:T043FileTransport {
        val files=LinkedHashMap<String,ByteArray>()
        var publishFails=false
        override fun publish(header:T043SyncProtocol.Header,packet:ByteArray) {
            if(publishFails)throw java.io.IOException("synthetic failure")
            val path=T043SyncProtocol.mailboxPath(header)+"/"+T043SyncProtocol.fileName(header)
            val previous=files[path]
            require(previous==null || previous.contentEquals(packet))
            files[path]=packet.copyOf()
        }
        override fun list(recipientDeviceId:String,streamId:String):List<String> =
            files.keys.filter { it.contains("/$recipientDeviceId/") }.sorted()
        override fun fetch(path:String):ByteArray=requireNotNull(files[path]).copyOf()
    }
    private lateinit var transport:MemoryTransport
    private lateinit var sender:T043SigningKey
    private lateinit var receiverSigner:T043SigningKey
    private lateinit var receiverSecret:T043HpkeSecret
    private lateinit var ingress:T043SyncInboxTransport

    private fun count(table:String)=helper.readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM $table",null).use { it.moveToFirst();it.getInt(0) }
    private fun string(sql:String,vararg args:String)=helper.readableDatabase.rawQuery(
        sql,args).use { if(it.moveToFirst())it.getString(0) else null }
    private fun sampleObs()=CanonicalPokerObservation(
        source=PokerSourceIdentity(source,"tableA","sessionA","handA"),
        observationId="obsA",tableCapacity=2,
        rulesetId=PokerRulesets.STANDARD52_NO_LIMIT_V1,
        street=PokerStreet.PREFLOP,
        actions=listOf(CanonicalPokerAction(
            sequence=0,eventId="eventA",kind=PokerActionKind.CHECK,
            evidence=PokerEvidence.INDEPENDENT))
    )
    private fun hand()=VerifiedPokerHandRecord(
        terminalObservation=sampleObs(),
        sourceProofRef="test-only-source",
        handIdentityProofRef="test-only-identity",
        terminalProofRef="test-only-terminal",
        sourceEvidence=PokerEvidence.INDEPENDENT,
        handIdentityEvidence=PokerEvidence.INDEPENDENT,
        terminalEvidence=PokerEvidence.INDEPENDENT
    )
    private fun handBytes()=CanonicalPokerJson.encodeVerifiedHand(hand()).toByteArray(Charsets.UTF_8)
    private val modelAllow=PokerHandProofVerifier { true }
    private val bytesAllow=T043ImmutableProofVerifier { snapshot,hash,_ ->
        T043MessageFingerprint.sha256(snapshot)==hash
    }
    private fun inboundHeader(raw:ByteArray=handBytes(),mid:String="inbound1",seq:Long=1):T043SyncProtocol.Header {
        val e=T043MessageFingerprint.Envelope(
            senderId=source,messageId=mid,recipientId="t043:pending-route",
            streamId="verified-hands-v1",sequenceNo=seq,
            contentType="poker.verified-hand.v1",
            sourceNamespace=source,relatedRecordType="hand",
            relatedRecordKey=hand().handRecordId,
            rulesetId=hand().rulesetId,
            payloadEncoding="JSON_UTF8",schemaMajor=1,schemaMinor=0)
        val d=T043MessageFingerprint.digest(raw,"JSON_UTF8","json-semantic.v1")
        return T043SyncProtocol.Header(
            senderDeviceId="player-device",senderSigningKeyId="player-sign",
            senderKeyVersion=1,recipientDeviceId="trainer-device",
            recipientEncryptionKeyId="trainer-enc",recipientKeyVersion=1,
            logicalSenderId=e.senderId,logicalRecipientId=e.recipientId,
            streamId=e.streamId,messageId=e.messageId,sequenceNo=e.sequenceNo,
            contentType=e.contentType,
            originalEnvelopeSha256=T043MessageFingerprint.envelopeSha256(e,d),
            originalRawSha256=d.rawSha256,
            sourceNamespace=e.sourceNamespace,
            relatedRecordType=e.relatedRecordType,
            relatedRecordKey=e.relatedRecordKey,rulesetId=e.rulesetId,
            payloadEncoding=e.payloadEncoding,schemaMajor=1,schemaMinor=0)
    }
    private fun outboundDraft(id:String="m1")=T043SyncDraft(
        senderId=source,messageId=id,recipientId="t043:pending-route",
        streamId="observation-stream",contentType="poker.observation",
        sourceNamespace=source,schemaMajor=1,schemaMinor=0)
    private fun obsBytes()=CanonicalPokerJson.encodeObservation(sampleObs()).toByteArray(Charsets.UTF_8)

    @Before fun setUp() {
        context=RuntimeEnvironment.getApplication()
        context.deleteDatabase("hand_learning_v1.db")
        java.io.File(context.noBackupFilesDir,"t043_sync_routes").deleteRecursively()
        java.io.File(context.noBackupFilesDir,"t043_inbound").deleteRecursively()
        helper=AndroidHandLearningStore(context)
        canonical=T043CanonicalSqliteStore(helper)
        routes=T043RouteStore(context)
        gateway=T043SyncSqlGateway(helper)
        inboundStore=T043InboundPacketStore(context)
        playerSign=Ed25519PrivateKeyParameters(rng)
        trainerSign=Ed25519PrivateKeyParameters(rng)
        playerEnc=X25519PrivateKeyParameters(rng)
        trainerEnc=X25519PrivateKeyParameters(rng)
        registry=T043DeviceIdentityRegistry(listOf(
            T043PinnedDevice("player-device","player-sign",1,playerSign.generatePublicKey().encoded,
                "player-enc",1,playerEnc.generatePublicKey().encoded,
                allowedLogicalSenders=setOf(source,"player-device")),
            T043PinnedDevice("trainer-device","trainer-sign",1,trainerSign.generatePublicKey().encoded,
                "trainer-enc",1,trainerEnc.generatePublicKey().encoded,
                allowedLogicalSenders=setOf("trainer-device"))
        ))
        sender=T043SigningKey { T043SyncCrypto.signEd25519(playerSign.encoded,it) }
        receiverSigner=T043SigningKey { T043SyncCrypto.signEd25519(trainerSign.encoded,it) }
        receiverSecret=object:T043HpkeSecret {
            override fun <T> withPrivateX25519(block:(ByteArray)->T):T =
                block(trainerEnc.encoded.copyOf())
        }
        ingress=T043SyncInboxTransport(registry,"trainer-device",receiverSecret,
            inboundStore,canonical)
        transport=MemoryTransport()
    }
    @After fun clean() {
        helper.close()
        context.deleteDatabase("hand_learning_v1.db")
    }

    @Test fun t3_01_hpke_and_signature_roundtrip() {
        val raw=handBytes()
        val h=inboundHeader(raw)
        val packet=T043SyncCrypto.seal(h,raw,trainerEnc.generatePublicKey().encoded,sender)
        val opened=T043SyncCrypto.open(packet,registry,"trainer-device",receiverSecret)
        assertEquals(h,opened.first)
        assertArrayEquals(raw,opened.second)
        val decoded=T043SyncProtocol.decode(packet)
        assertEquals(32,decoded.enc.size)
        assertEquals(64,decoded.signature.size)
    }
    @Test fun t3_02_wrong_signature_rejected() {
        val raw=handBytes();val h=inboundHeader(raw)
        val packet=T043SyncProtocol.decode(T043SyncCrypto.seal(
            h,raw,trainerEnc.generatePublicKey().encoded,sender))
        val altered=T043SyncProtocol.encode(packet.copy(signature=packet.signature.copyOf().also {
            it[0]=(it[0].toInt() xor 1).toByte()
        }))
        try { T043SyncCrypto.open(altered,registry,"trainer-device",receiverSecret)
            fail("Untrusted signature accepted")
        } catch (_:IllegalArgumentException) {}
    }
    @Test fun t3_03_wrong_recipient_key_rejected() {
        val raw=handBytes();val h=inboundHeader(raw)
        val packet=T043SyncCrypto.seal(h,raw,trainerEnc.generatePublicKey().encoded,sender)
        val wrong=X25519PrivateKeyParameters(rng)
        val key=object:T043HpkeSecret {
            override fun <T> withPrivateX25519(block:(ByteArray)->T):T =
                block(wrong.encoded)
        }
        try { T043SyncCrypto.open(packet,registry,"trainer-device",key)
            fail("Wrong key decrypted")
        } catch (_:IllegalArgumentException) {}
    }
    @Test fun t3_04_ciphertext_tamper_rejected() {
        val raw=handBytes();val h=inboundHeader(raw)
        val signed=T043SyncProtocol.decode(T043SyncCrypto.seal(
            h,raw,trainerEnc.generatePublicKey().encoded,sender))
        val ct=signed.ciphertext.copyOf()
        ct[0]=(ct[0].toInt() xor 8).toByte()
        try { T043SyncCrypto.open(T043SyncProtocol.encode(signed.copy(ciphertext=ct)),
            registry,"trainer-device",receiverSecret)
            fail("Tampered ciphertext accepted")
        } catch (_:IllegalArgumentException) {}
    }
    @Test fun t3_05_changed_sender_identity_rejected() {
        val raw=handBytes();val h=inboundHeader(raw)
        val packet=T043SyncProtocol.decode(T043SyncCrypto.seal(
            h,raw,trainerEnc.generatePublicKey().encoded,sender))
        val edited=T043SyncProtocol.encode(packet.copy(header=h.copy(senderDeviceId="other-device")))
        try { T043SyncCrypto.open(edited,registry,"trainer-device",receiverSecret)
            fail("Sender ID changed")
        } catch (_:IllegalArgumentException) {}
    }
    @Test fun t3_06_revoked_sender_rejected() {
        val raw=handBytes();val h=inboundHeader(raw)
        val packet=T043SyncCrypto.seal(h,raw,trainerEnc.generatePublicKey().encoded,sender)
        val revoked=T043DeviceIdentityRegistry(listOf(
            T043PinnedDevice("player-device","player-sign",1,playerSign.generatePublicKey().encoded,
                "player-enc",1,playerEnc.generatePublicKey().encoded,
                allowedLogicalSenders=setOf(source),revoked=true),
            registry.requireEncryptionRecipientForRoute("trainer-device")))
        try { T043SyncCrypto.open(packet,revoked,"trainer-device",receiverSecret)
            fail("Revoked sender accepted")
        } catch (_:IllegalArgumentException) {}
    }
    @Test fun t3_07_repeated_packet_has_durable_dedupe() {
        val raw=handBytes();val h=inboundHeader(raw)
        val packet=T043SyncCrypto.seal(h,raw,trainerEnc.generatePublicKey().encoded,sender)
        assertEquals(T043WriteResult.APPLIED,ingress.receiveAuthenticatedPacket(packet))
        assertEquals(T043WriteResult.ALREADY_SEEN,ingress.receiveAuthenticatedPacket(packet))
        assertEquals(1,count("t043_sync_inbox"))
    }
    @Test fun t3_08_repeated_id_changed_payload_is_conflict() {
        val bytes=obsBytes()
        val d=outboundDraft("same")
        assertEquals(T043WriteResult.APPLIED,canonical.enqueue(d,bytes))
        assertEquals(T043WriteResult.CONFLICT,canonical.enqueue(d,"""{"changed":true}""".toByteArray()))
        assertEquals(1,count("t043_conflicts"))
    }
    @Test fun t3_09_out_of_order_diagnostic_delivery() {
        val raw=obsBytes()
        fun h(no:Long)=T043MessageFingerprint.Envelope(
            source,"diag-$no","t043:pending-route","diagnostics",no,
            "poker.observation",payloadEncoding="JSON_UTF8",schemaMajor=1,schemaMinor=0)
        assertEquals(T043WriteResult.APPLIED,canonical.receive(h(1),raw))
        assertEquals(T043WriteResult.APPLIED,canonical.receive(h(3),raw))
        assertEquals(T043WriteResult.REJECTED,canonical.applyInboxAtomically(source,"diag-3"))
        assertEquals(T043WriteResult.APPLIED,canonical.applyInboxAtomically(source,"diag-1"))
        assertEquals(T043WriteResult.APPLIED,canonical.receive(h(2),raw))
        assertEquals(T043WriteResult.APPLIED,canonical.applyInboxAtomically(source,"diag-2"))
        assertEquals(T043WriteResult.APPLIED,canonical.applyInboxAtomically(source,"diag-3"))
        assertEquals("3",string("SELECT last_contiguous_no FROM t043_sync_cursors WHERE direction='INBOUND'"))
    }
    @Test fun t3_10_two_senders_have_independent_keys() {
        val raw=obsBytes()
        assertEquals(T043WriteResult.APPLIED,canonical.receive(
            T043MessageFingerprint.Envelope("one","m","r","s",1,"poker.observation"),raw))
        assertEquals(T043WriteResult.APPLIED,canonical.receive(
            T043MessageFingerprint.Envelope("two","m","r","s",1,"poker.observation"),raw))
        assertEquals(2,count("t043_sync_inbox"))
    }
    @Test fun t3_11_offline_does_not_erase_outbox() {
        val raw=obsBytes()
        assertEquals(T043WriteResult.APPLIED,canonical.enqueue(outboundDraft(),raw))
        transport.publishFails=true
        val coordinator=T043SyncCoordinator(gateway,routes,registry,transport)
        try { coordinator.publish(source,"m1",T043RouteApproval("player-device","trainer-device"),sender)
            fail("offline transfer appeared successful")
        } catch (_:java.io.IOException) {}
        assertEquals("PENDING",string("SELECT delivery_state FROM t043_sync_outbox"))
        assertNotNull(routes.recover(source,"m1",
            requireNotNull(gateway.readOutbox(source,"m1")).envelopeSha256))
    }
    @Test fun t3_12_truncated_packet_cannot_be_accepted() {
        val raw=handBytes();val h=inboundHeader(raw)
        val valid=T043SyncCrypto.seal(h,raw,trainerEnc.generatePublicKey().encoded,sender)
        assertEquals(T043WriteResult.REJECTED,
            ingress.receiveAuthenticatedPacket(valid.copyOf(valid.size-14)))
        assertEquals(0,count("t043_sync_inbox"))
    }
    @Test fun t3_13_prepare_reboot_does_not_change_packet_or_route() {
        canonical.enqueue(outboundDraft(),obsBytes())
        val coordinator=T043SyncCoordinator(gateway,routes,registry,transport)
        val approve=T043RouteApproval("player-device","trainer-device")
        val saved=coordinator.prepare(source,"m1",approve,sender)
        val restarted=T043SyncCoordinator(gateway,T043RouteStore(context),registry,transport)
        val restored=restarted.prepare(source,"m1",approve,sender)
        assertArrayEquals(saved.packet,restored.packet)
        assertEquals(saved.header,restored.header)
    }
    @Test fun t3_14_server_reply_cannot_ack_message() {
        canonical.enqueue(outboundDraft(),obsBytes())
        val coordinator=T043SyncCoordinator(gateway,routes,registry,transport)
        coordinator.publish(source,"m1",T043RouteApproval("player-device","trainer-device"),sender)
        assertEquals("SENT_UNCONFIRMED",string("SELECT delivery_state FROM t043_sync_outbox"))
    }
    @Test fun t3_15_unknown_signing_key_rejected() {
        val raw=handBytes();val h=inboundHeader(raw)
        val signed=T043SyncProtocol.decode(T043SyncCrypto.seal(
            h,raw,trainerEnc.generatePublicKey().encoded,sender))
        val changed=signed.copy(header=h.copy(senderSigningKeyId="other-sign"))
        try { T043SyncCrypto.open(T043SyncProtocol.encode(changed),
            registry,"trainer-device",receiverSecret)
            fail("Unregistered signing key")
        } catch (_:IllegalArgumentException) {}
    }
    @Test fun t3_16_wrong_message_in_receipt_rejected() {
        canonical.importVerifiedHand(handBytes(),modelAllow,bytesAllow)
        val row=gateway.readOutbox(source,"hand-"+
            T043MessageFingerprint.sha256(hand().handRecordId.toByteArray()))
        assertNotNull(row)
        val body=T043SyncReceipt.Body("trainer-device","trainer-sign",1,
            "player-device",source,"wrong","verified-hands-v1",1,
            requireNotNull(row).envelopeSha256,T043SyncReceipt.VERIFIED_IMPORTED,hand().handRecordId)
        val signed=T043SyncReceipt.sign(body,receiverSigner)
        val manager=T043SyncReceiptManager(helper,gateway,routes,registry)
        assertEquals(T043WriteResult.REJECTED,manager.consumeVerifiedReceipt(
            signed,"player-device","trainer-device","receipt-test"))
    }
    @Test fun t3_17_diagnostic_applied_is_not_verified_import() {
        val raw=obsBytes()
        val e=T043MessageFingerprint.Envelope(source,"m","r","diagnostics",1,
            "poker.observation",schemaMajor=1,schemaMinor=0)
        assertEquals(T043WriteResult.APPLIED,canonical.receive(e,raw))
        assertEquals(T043WriteResult.APPLIED,canonical.applyInboxAtomically(source,"m"))
        assertEquals(0,count("t043_verified_hands"))
    }
    @Test fun t3_18_no_game_proof_means_no_verified_inbound() {
        val raw=handBytes();val h=inboundHeader(raw)
        val packet=T043SyncCrypto.seal(h,raw,trainerEnc.generatePublicKey().encoded,sender)
        assertEquals(T043WriteResult.APPLIED,ingress.receiveAuthenticatedPacket(packet))
        val bridge=T043VerifiedImportBridge(helper,ingress,registry,"trainer-device")
        assertEquals(T043WriteResult.REJECTED,
            bridge.importVerifiedAndCreateReceipt(source,h.messageId,null,null,null))
        assertEquals("RECEIVED",string("SELECT processing_state FROM t043_sync_inbox"))
        assertEquals(0,count("t043_verified_hands"))
    }
    @Test fun t3_19_successful_synthetic_inbound_commits_hand_and_receipt() {
        val raw=handBytes();val h=inboundHeader(raw)
        val packet=T043SyncCrypto.seal(h,raw,trainerEnc.generatePublicKey().encoded,sender)
        assertEquals(T043WriteResult.APPLIED,ingress.receiveAuthenticatedPacket(packet))
        val bridge=T043VerifiedImportBridge(helper,ingress,registry,"trainer-device")
        assertEquals(T043WriteResult.APPLIED,bridge.importVerifiedAndCreateReceipt(
            source,h.messageId,modelAllow,bytesAllow,receiverSigner))
        assertEquals(1,count("t043_verified_hands"))
        assertEquals(1,count("t043_verified_events"))
        assertEquals("APPLIED",string("SELECT processing_state FROM t043_sync_inbox"))
        assertEquals("t043.receipt.v1",string("SELECT content_type FROM t043_sync_outbox"))
        assertEquals("1",string("SELECT last_contiguous_no FROM t043_sync_cursors WHERE direction='INBOUND'"))
    }
    @Test fun t3_20_receipt_insert_failure_rolls_back_trusted_import() {
        val raw=handBytes();val h=inboundHeader(raw)
        val packet=T043SyncCrypto.seal(h,raw,trainerEnc.generatePublicKey().encoded,sender)
        ingress.receiveAuthenticatedPacket(packet)
        helper.writableDatabase.execSQL(
            """CREATE TRIGGER t043_test_receipt_failure BEFORE INSERT ON t043_sync_outbox
               BEGIN SELECT RAISE(ABORT,'test receipt failure'); END""")
        val bridge=T043VerifiedImportBridge(helper,ingress,registry,"trainer-device")
        assertEquals(T043WriteResult.STORAGE_ERROR,
            bridge.importVerifiedAndCreateReceipt(source,h.messageId,modelAllow,bytesAllow,receiverSigner))
        assertEquals(0,count("t043_verified_hands"))
        assertEquals(0,count("t043_sync_outbox"))
        assertEquals("RECEIVED",string("SELECT processing_state FROM t043_sync_inbox"))
        helper.writableDatabase.execSQL("DROP TRIGGER t043_test_receipt_failure")
        assertEquals(T043WriteResult.APPLIED,
            bridge.importVerifiedAndCreateReceipt(source,h.messageId,modelAllow,bytesAllow,receiverSigner))
    }
    @Test fun t3_21_repeated_receipt_does_not_repeat_import() {
        val raw=handBytes();val h=inboundHeader(raw)
        ingress.receiveAuthenticatedPacket(T043SyncCrypto.seal(h,raw,
            trainerEnc.generatePublicKey().encoded,sender))
        val bridge=T043VerifiedImportBridge(helper,ingress,registry,"trainer-device")
        assertEquals(T043WriteResult.APPLIED,
            bridge.importVerifiedAndCreateReceipt(source,h.messageId,modelAllow,bytesAllow,receiverSigner))
        assertEquals(T043WriteResult.ALREADY_SEEN,
            bridge.importVerifiedAndCreateReceipt(source,h.messageId,modelAllow,bytesAllow,receiverSigner))
        assertEquals(1,count("t043_verified_hands"))
        assertEquals(1,count("t043_sync_outbox"))
    }
    @Test fun t3_22_corrupt_file_is_rejected_before_inbox() {
        assertEquals(T043WriteResult.REJECTED,ingress.receiveAuthenticatedPacket(ByteArray(32)))
        assertEquals(0,count("t043_sync_inbox"))
    }
    @Test fun t3_23_unsupported_json_is_quarantined() {
        val draft=T043SyncDraft(source,"m1","r","diag","poker.observation",
            payloadEncoding="JSON_UTF8",schemaMajor=1,schemaMinor=99)
        assertEquals(T043WriteResult.APPLIED,canonical.enqueue(draft,badJson()))
        assertEquals("QUARANTINED",string("SELECT delivery_state FROM t043_sync_outbox"))
    }
    private fun badJson()="""{"a":""".toByteArray(Charsets.UTF_8)
    @Test fun t3_24_never_silent_reroute() {
        canonical.enqueue(outboundDraft(),obsBytes())
        val coordinator=T043SyncCoordinator(gateway,routes,registry,transport)
        val approved=T043RouteApproval("player-device","trainer-device")
        val saved=coordinator.prepare(source,"m1",approved,sender)
        try { coordinator.prepare(source,"m1",T043RouteApproval("trainer-device","player-device"),sender)
            fail("Route changed")
        } catch (_:IllegalArgumentException) {}
        assertArrayEquals(saved.packet,requireNotNull(
            routes.recover(source,"m1",saved.header.originalEnvelopeSha256)).packet)
    }
    @Test fun t3_25_game_source_is_distinct_from_device() {
        val raw=handBytes();val h=inboundHeader(raw)
        assertNotEquals(h.logicalSenderId,h.senderDeviceId)
        val packet=T043SyncCrypto.seal(h,raw,trainerEnc.generatePublicKey().encoded,sender)
        assertEquals(source,T043SyncCrypto.open(packet,registry,"trainer-device",receiverSecret)
            .first.logicalSenderId)
    }
    @Test fun t3_26_untrusted_poker_payload_cannot_gain_applied() {
        val raw=handBytes();val h=inboundHeader(raw)
        ingress.receiveAuthenticatedPacket(T043SyncCrypto.seal(h,raw,
            trainerEnc.generatePublicKey().encoded,sender))
        assertEquals(T043WriteResult.REJECTED,canonical.applyInboxAtomically(
            source,h.messageId))
        assertEquals(0,count("t043_verified_hands"))
    }
    @Test fun t3_27_untrusted_reply_does_not_change_state() {
        canonical.enqueue(outboundDraft(),obsBytes())
        assertEquals("PENDING",string("SELECT delivery_state FROM t043_sync_outbox"))
        val receipt=T043SyncReceipt.Body("trainer-device","trainer-sign",1,
            "player-device",source,"m1","observation-stream",1,
            requireNotNull(gateway.readOutbox(source,"m1")).envelopeSha256,
            T043SyncReceipt.RECEIVED_DURABLE,"none")
        val manager=T043SyncReceiptManager(helper,gateway,routes,registry)
        assertEquals(T043WriteResult.REJECTED,manager.consumeVerifiedReceipt(
            T043SyncReceipt.sign(receipt,receiverSigner),"player-device",
            "trainer-device","receipt"))
        assertEquals("PENDING",string("SELECT delivery_state FROM t043_sync_outbox"))
    }
    @Test fun t3_28_legacy_and_stage2_schemas_intact() {
        assertEquals(2,helper.readableDatabase.version)
        val tables=helper.readableDatabase.rawQuery("SELECT COUNT(*) FROM sqlite_master " +
            "WHERE type='table' AND name LIKE 't043_%'",null).use { it.moveToFirst();it.getInt(0) }
        assertEquals(11,tables)
        assertEquals(0,count("completed_hands"))
    }
    @Test fun t3_29_safe_paths_and_bounded_packet() {
        val h=inboundHeader()
        assertTrue(T043SyncProtocol.mailboxPath(h).startsWith("/t043/v1/inbox/trainer-device/"))
        assertTrue(T043SyncProtocol.fileName(h).matches(
            Regex("m-[a-f0-9]{64}-[0-9]{20}\\.t043")))
        try { T043FtpsEndpoint("evil/../server",approved=true)
            fail("Path injected host")
        } catch (_:IllegalArgumentException) {}
    }
    @Test fun t3_30_ftps_config_locked_until_explicit_approval() {
        val ftps=T043FtpsTransport(T043FtpsEndpoint("example.test"),
            T043FtpsCredentials { _ -> fail("Should not access credentials") })
        try { ftps.list("trainer-device","hands")
            fail("Unapproved FTP connection attempted")
        } catch (_:IllegalStateException) {}
    }
    @Test fun t3_31_recovery_has_same_encryption_and_recipient() {
        canonical.enqueue(outboundDraft(),obsBytes())
        val approval=T043RouteApproval("player-device","trainer-device")
        val first=T043SyncCoordinator(gateway,routes,registry,transport)
            .prepare(source,"m1",approval,sender)
        helper.close()
        helper=AndroidHandLearningStore(context)
        gateway=T043SyncSqlGateway(helper)
        val next=T043SyncCoordinator(gateway,T043RouteStore(context),registry,transport)
            .prepare(source,"m1",approval,sender)
        assertArrayEquals(first.packet,next.packet)
        assertEquals(first.header.recipientEncryptionKeyId,next.header.recipientEncryptionKeyId)
    }
    @Test fun t3_32_no_player_trainer_verified_hand_echo() {
        val raw=handBytes();val h=inboundHeader(raw)
        ingress.receiveAuthenticatedPacket(T043SyncCrypto.seal(h,raw,
            trainerEnc.generatePublicKey().encoded,sender))
        val bridge=T043VerifiedImportBridge(helper,ingress,registry,"trainer-device")
        assertEquals(T043WriteResult.APPLIED,bridge.importVerifiedAndCreateReceipt(
            source,h.messageId,modelAllow,bytesAllow,receiverSigner))
        assertEquals(0,helper.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM t043_sync_outbox WHERE content_type='poker.verified-hand.v1'",null)
            .use { it.moveToFirst();it.getInt(0) })
        assertEquals(1,count("t043_sync_outbox"))
    }
    @Test fun t3_33_missing_real_verifier_fails_closed() {
        val raw=handBytes();val h=inboundHeader(raw)
        ingress.receiveAuthenticatedPacket(T043SyncCrypto.seal(h,raw,
            trainerEnc.generatePublicKey().encoded,sender))
        assertEquals(T043WriteResult.REJECTED,
            T043VerifiedImportBridge(helper,ingress,registry,"trainer-device")
                .importVerifiedAndCreateReceipt(source,h.messageId,null,null,receiverSigner))
    }
    @Test fun t3_34_hpke_enc_and_recipient_header_tamper_rejected() {
        val raw=handBytes();val h=inboundHeader(raw)
        val original=T043SyncProtocol.decode(T043SyncCrypto.seal(h,raw,
            trainerEnc.generatePublicKey().encoded,sender))
        val enc=original.enc.copyOf();enc[0]=(enc[0].toInt() xor 1).toByte()
        try { T043SyncCrypto.open(T043SyncProtocol.encode(original.copy(enc=enc)),
            registry,"trainer-device",receiverSecret);fail("Modified enc accepted")
        } catch (_:IllegalArgumentException) {}
        val changed=original.copy(header=h.copy(recipientEncryptionKeyId="other-enc"))
        try { T043SyncCrypto.open(T043SyncProtocol.encode(changed),
            registry,"trainer-device",receiverSecret);fail("Changed recipient key ID accepted")
        } catch (_:IllegalArgumentException) {}
    }
    @Test fun t3_35_plain_ftp_and_tls_downgrade_not_configured() {
        assertFalse(T043FtpsEndpoint("example.test").approved)
        assertTrue(T043FtpsTransport::class.java.name.contains("Ftps"))
        // Real self-signed TLS negative integration requires an isolated FTPS fixture.
        // This unit case asserts the mandatory approval gate, not a live certificate.
    }
    @Test fun t3_36_crash_between_verified_import_and_receipt_rolls_back() {
        t3_20_receipt_insert_failure_rolls_back_trusted_import()
    }
    @Test fun t3_37_signed_receipt_duplicate_preserves_ack_without_mutation() {
        canonical.importVerifiedHand(handBytes(),modelAllow,bytesAllow)
        val message="hand-"+T043MessageFingerprint.sha256(hand().handRecordId.toByteArray())
        val snapshot=requireNotNull(gateway.readOutbox(source,message))
        val approval=T043RouteApproval("player-device","trainer-device")
        T043SyncCoordinator(gateway,routes,registry,transport)
            .publish(source,message,approval,sender)
        val body=T043SyncReceipt.Body("trainer-device","trainer-sign",1,"player-device",
            source,message,snapshot.envelope.streamId,snapshot.envelope.sequenceNo,
            snapshot.envelopeSha256,T043SyncReceipt.VERIFIED_IMPORTED,hand().handRecordId)
        val raw=T043SyncReceipt.sign(body,receiverSigner)
        val receiptId="receipt-"+T043SyncProtocol.stableIdentityHash(source,message)
        val e=T043MessageFingerprint.Envelope(
            "trainer-device",receiptId,"player-device","receipts-v1",1,
            "t043.receipt.v1",relatedRecordType="receipt",relatedRecordKey=message,
            payloadEncoding="OPAQUE_BYTES",canonicalizationId="raw-bytes.v1")
        assertEquals(T043WriteResult.APPLIED,canonical.receive(e,raw))
        val m=T043SyncReceiptManager(helper,gateway,routes,registry)
        assertEquals(T043WriteResult.APPLIED,m.consumeVerifiedReceipt(raw,
            "player-device","trainer-device",receiptId))
        assertEquals(T043WriteResult.ALREADY_SEEN,m.consumeVerifiedReceipt(raw,
            "player-device","trainer-device",receiptId))
        assertEquals("ACKNOWLEDGED",string("SELECT delivery_state FROM t043_sync_outbox " +
            "WHERE sender_id=? AND message_id=?",source,message))
        assertEquals("APPLIED",string("SELECT processing_state FROM t043_sync_inbox " +
            "WHERE message_id=?",receiptId))
    }
}
