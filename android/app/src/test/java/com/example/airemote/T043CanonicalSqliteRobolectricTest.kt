package com.example.airemote

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.nio.charset.StandardCharsets

/** Real Android SQLiteOpenHelper + SQLiteDatabase + ContentValues via Robolectric.
 * No emulator, external app, signed APK, FTP, Trainer, or user database. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class T043CanonicalSqliteRobolectricTest {
    private lateinit var context: Context
    private lateinit var helper: AndroidHandLearningStore
    private lateinit var storage: T043CanonicalSqliteStore
    private val dbName = "hand_learning_v1.db"

    @Before fun prepare() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(dbName)
        helper = AndroidHandLearningStore(context)
        storage = T043CanonicalSqliteStore(helper)
    }

    @After fun cleanup() {
        helper.close()
        context.deleteDatabase(dbName)
    }

    private fun count(table: String): Int =
        helper.readableDatabase.rawQuery("SELECT COUNT(*) FROM $table", null).use { c ->
            assertTrue(c.moveToFirst())
            c.getInt(0)
        }

    private fun queryString(sql: String, vararg args: String): String? =
        helper.readableDatabase.rawQuery(sql, args).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    private fun sampleObservation(id: String = "obs-1", amount: Long? = 2L) =
        CanonicalPokerObservation(
            source = PokerSourceIdentity("fixtures.t043", "table-1", "session-1", "hand-1"),
            observationId = id, tableCapacity = 2,
            rulesetId = PokerRulesets.STANDARD52_NO_LIMIT_V1,
            street = PokerStreet.PREFLOP,
            actions = listOf(CanonicalPokerAction(
                sequence = 0, kind = PokerActionKind.CALL, amountChips = amount,
                eventId = "event-1", evidence = PokerEvidence.INDEPENDENT))
        )

    private fun sampleHand() = VerifiedPokerHandRecord(
        terminalObservation = sampleObservation(),
        sourceProofRef = "fixture-source-proof",
        handIdentityProofRef = "fixture-hand-proof",
        terminalProofRef = "fixture-terminal-proof",
        sourceEvidence = PokerEvidence.INDEPENDENT,
        handIdentityEvidence = PokerEvidence.INDEPENDENT,
        terminalEvidence = PokerEvidence.INDEPENDENT
    )

    private fun obsBytes(id: String = "obs-1", amount: Long? = 2L): ByteArray =
        CanonicalPokerJson.encodeObservation(sampleObservation(id,amount))
            .toByteArray(StandardCharsets.UTF_8)

    private fun handBytes(): ByteArray =
        CanonicalPokerJson.encodeVerifiedHand(sampleHand())
            .toByteArray(StandardCharsets.UTF_8)

    private val acceptModel = PokerHandProofVerifier { true }
    private val acceptBytes = T043ImmutableProofVerifier { snapshot, digest, record ->
        digest == T043MessageFingerprint.sha256(snapshot) &&
            record.rulesetId == PokerRulesets.STANDARD52_NO_LIMIT_V1
    }

    private fun envelope(id: String, seq: Long, kind: String = "poker.observation") =
        T043MessageFingerprint.Envelope(
            senderId = "sender-1",messageId = id,recipientId = "recipient-1",
            streamId = "observation-stream",sequenceNo = seq,
            contentType = kind,schemaMajor = 1,schemaMinor = 0)

    @Test fun freshDatabaseCreatedByRealHelper() {
        val db = helper.writableDatabase
        assertEquals(2, db.version)
        assertEquals(0, count("completed_hands"))
        assertEquals(0, count("opponents"))
        assertEquals(0, count("hand_hero_actions"))
        assertEquals(0, count("hand_opponent_facts"))
        assertEquals(0, count("t043_sync_inbox"))
        val tableCount = db.rawQuery(
            "SELECT COUNT(*) FROM sqlite_master WHERE type='table' " +
                "AND (name LIKE 't043_%' OR name IN " +
                "('completed_hands','opponents','hand_hero_actions','hand_opponent_facts'))",
            null).use { c -> c.moveToFirst();c.getInt(0) }
        assertEquals(15,tableCount)
        assertEquals("ok",queryString("PRAGMA integrity_check"))
        assertEquals(0, db.rawQuery("PRAGMA foreign_key_check",null).use { it.count })
    }

    @Test fun realHelperUpgradesFilledV1PreservingEveryLegacyTable() {
        val old = context.openOrCreateDatabase(dbName, Context.MODE_PRIVATE,null)
        old.execSQL("""CREATE TABLE completed_hands (
            table_key TEXT NOT NULL, hand_key TEXT NOT NULL,
            capture_epoch INTEGER NOT NULL, finished_ms INTEGER NOT NULL,
            hero_net REAL,
            PRIMARY KEY(table_key, hand_key))""")
        old.execSQL("""CREATE TABLE opponents (
            opponent_key TEXT PRIMARY KEY,
            observed_hands INTEGER NOT NULL DEFAULT 0,
            vpip_seen INTEGER NOT NULL DEFAULT 0,
            vpip_opportunities INTEGER NOT NULL DEFAULT 0,
            pfr_seen INTEGER NOT NULL DEFAULT 0,
            pfr_opportunities INTEGER NOT NULL DEFAULT 0,
            aggression_actions INTEGER NOT NULL DEFAULT 0,
            postflop_calls INTEGER NOT NULL DEFAULT 0,
            folds_facing_bet INTEGER NOT NULL DEFAULT 0,
            faced_bet_opportunities INTEGER NOT NULL DEFAULT 0,
            showdowns_seen INTEGER NOT NULL DEFAULT 0,
            showdowns_won INTEGER NOT NULL DEFAULT 0)""")
        old.execSQL("""CREATE TABLE hand_hero_actions (
            table_key TEXT NOT NULL, hand_key TEXT NOT NULL,
            action_key TEXT NOT NULL, street TEXT NOT NULL, action TEXT NOT NULL,
            frame_seq INTEGER NOT NULL, amount_chips REAL,
            PRIMARY KEY(table_key, hand_key, action_key))""")
        old.execSQL("""CREATE TABLE hand_opponent_facts (
            table_key TEXT NOT NULL, hand_key TEXT NOT NULL,
            opponent_key TEXT NOT NULL, vpip INTEGER, pfr INTEGER,
            aggression INTEGER, calls INTEGER, faced_bet INTEGER,
            fold_vs_bet INTEGER, showdown INTEGER, showdown_won INTEGER,
            PRIMARY KEY(table_key, hand_key, opponent_key))""")
        old.execSQL("INSERT INTO completed_hands VALUES('table','hand',7,123,42.5)")
        old.execSQL("INSERT INTO opponents VALUES('opponent',1,1,2,3,4,5,6,7,8,9,10)")
        old.execSQL("INSERT INTO hand_hero_actions VALUES('table','hand','action','FLOP','CALL',12,8.0)")
        old.execSQL("INSERT INTO hand_opponent_facts VALUES('table','hand','opponent',1,0,2,3,1,0,1,0)")
        val names=listOf("completed_hands","opponents","hand_hero_actions","hand_opponent_facts")
        val snapshot = names.associateWith { name ->
            old.rawQuery("SELECT * FROM $name",null).use { c ->
                c.moveToFirst()
                (0 until c.columnCount).map { index ->
                    if (c.isNull(index)) "NULL" else c.getString(index)
                }
            }
        }
        val sql = names.associateWith { name ->
            old.rawQuery("SELECT sql FROM sqlite_master WHERE name=?",arrayOf(name)).use { c ->
                c.moveToFirst();c.getString(0)
            }
        }
        val columns = names.associateWith { name ->
            old.rawQuery("PRAGMA table_info($name)",null).use { c ->
                buildList { while (c.moveToNext()) add(c.getString(1)) }
            }
        }
        old.version=1
        old.close()

        assertEquals(2,helper.writableDatabase.version)
        for (name in names) {
            helper.readableDatabase.rawQuery("SELECT * FROM $name",null).use { c ->
                assertTrue("missing $name",c.moveToFirst())
                assertEquals(snapshot[name],(0 until c.columnCount).map { index ->
                    if (c.isNull(index)) "NULL" else c.getString(index)
                })
                assertFalse(c.moveToNext())
            }
            assertEquals(sql[name],queryString("SELECT sql FROM sqlite_master WHERE name=?",name))
            val fresh=helper.readableDatabase.rawQuery("PRAGMA table_info($name)",null).use { c ->
                buildList { while (c.moveToNext()) add(c.getString(1)) }
            }
            assertEquals(columns[name],fresh)
        }
        assertEquals(1,helper.completedHandsCount())
        assertTrue(helper.recentHandOutcomes(10).first().contains("hand"))
        assertNotNull(helper.opponentProfile("opponent"))
        assertEquals("ok",queryString("PRAGMA integrity_check"))
    }

    @Test fun oldCommitHandAndProfileStillOperateAfterMigration() {
        val original = VerifiedFinishedHand(
            tableKey="t",handKey="h",captureEpoch=1,firstFrame=10,
            terminalFrame=20,finishedAtElapsedMs=30,
            sourceVerified=true,terminalConfirmedByIndependentFrames=true,
            opponents=listOf(OpponentHandFacts("opp",true,vpip=true)),
            verifiedHeroActions=emptyList(),heroNetChips=25.0)
        assertEquals(HandLearningResult.APPLIED,helper.commitHandAtomically(original))
        assertEquals(HandLearningResult.ALREADY_SEEN,helper.commitHandAtomically(original))
        assertEquals(1,helper.completedHandsCount())
        assertEquals(1,helper.opponentProfile("opp")?.observedHands)
        assertEquals(1,helper.opponentProfile("opp")?.vpipSeen)
        assertTrue(helper.recentHandOutcomes(3).first().contains("h"))
    }

    @Test fun observationImportIsIdempotentAndKeepsOriginalBytes() {
        val bytes=obsBytes()
        assertEquals(T043WriteResult.APPLIED,storage.importObservation(bytes))
        assertEquals(T043WriteResult.ALREADY_SEEN,storage.importObservation(bytes))
        assertEquals(T043WriteResult.CONFLICT,storage.importObservation(obsBytes(amount=99L)))
        assertEquals(1,count("t043_observations"))
        assertEquals(1,count("t043_conflicts"))
        helper.readableDatabase.rawQuery("SELECT payload_bytes,raw_sha256 FROM t043_observations",null)
            .use { c ->
                assertTrue(c.moveToFirst())
                assertArrayEquals(bytes,c.getBlob(0))
                assertEquals(T043MessageFingerprint.sha256(c.getBlob(0)),c.getString(1))
            }
    }

    @Test fun verifiedHandRequiresTwoVerifiersAndWritesAtomicOutbox() {
        val bytes=handBytes()
        assertEquals(T043WriteResult.REJECTED,storage.importVerifiedHand(bytes,null,null))
        assertEquals(T043WriteResult.REJECTED,storage.importVerifiedHand(
            bytes,PokerHandProofVerifier { false },acceptBytes))
        assertEquals(T043WriteResult.REJECTED,storage.importVerifiedHand(
            bytes,acceptModel,T043ImmutableProofVerifier { _,_,_ -> false }))
        assertEquals(0,count("t043_verified_hands"))
        assertEquals(T043WriteResult.APPLIED,storage.importVerifiedHand(bytes,acceptModel,acceptBytes))
        assertEquals(1,count("t043_verified_hands"))
        assertEquals(1,count("t043_verified_events"))
        assertEquals(1,count("t043_sync_outbox"))
        assertArrayEquals(bytes,helper.readableDatabase.rawQuery(
            "SELECT payload_bytes FROM t043_sync_outbox",null).use { c ->
            c.moveToFirst();c.getBlob(0)
        })
        assertEquals("t043:pending-route",queryString(
            "SELECT recipient_id FROM t043_sync_outbox"))
        assertEquals(T043WriteResult.ALREADY_SEEN,storage.importVerifiedHand(bytes,acceptModel,acceptBytes))
        assertEquals(1,count("t043_sync_outbox"))
    }

    @Test fun verifiedHandQueueFailureRollsBackHandEventsAndCounter() {
        val db=helper.writableDatabase
        db.execSQL("""CREATE TRIGGER t043_test_failed_queue BEFORE INSERT ON t043_sync_outbox
            BEGIN SELECT RAISE(ABORT,'simulated interruption'); END""")
        val bytes=handBytes()
        assertEquals(T043WriteResult.STORAGE_ERROR,
            storage.importVerifiedHand(bytes,acceptModel,acceptBytes))
        assertEquals(0,count("t043_verified_hands"))
        assertEquals(0,count("t043_verified_events"))
        assertEquals(0,count("t043_sync_outbox"))
        assertEquals(0,count("t043_sync_cursors"))
        db.execSQL("DROP TRIGGER t043_test_failed_queue")
        assertEquals(T043WriteResult.APPLIED,
            storage.importVerifiedHand(bytes,acceptModel,acceptBytes))
        assertEquals(1,count("t043_verified_hands"))
        assertEquals(1,count("t043_sync_outbox"))
    }

    @Test fun enqueueCreatesOnePersistentMessageAndRejectsTamper() {
        val draft=T043SyncDraft("sender","id1","receiver","stream","diagnostic")
        val raw="""{"b":2,"a":1}""".toByteArray(StandardCharsets.UTF_8)
        assertEquals(T043WriteResult.APPLIED,storage.enqueue(draft,raw))
        assertEquals(T043WriteResult.ALREADY_SEEN,storage.enqueue(draft,raw))
        assertEquals(T043WriteResult.ALREADY_SEEN,
            storage.enqueue(draft,"""{"a":1,"b":2}""".toByteArray(StandardCharsets.UTF_8)))
        assertEquals(T043WriteResult.CONFLICT,
            storage.enqueue(draft,"""{"a":3}""".toByteArray(StandardCharsets.UTF_8)))
        assertEquals(1,count("t043_sync_outbox"))
        assertEquals("1",queryString("SELECT sequence_no FROM t043_sync_outbox"))
        assertEquals("1",queryString("SELECT last_allocated_no FROM t043_sync_cursors"))
    }

    @Test fun receiveAndApplyDiagnosticsInOrderWhileRefusingVerifiedPayload() {
        val raw=obsBytes()
        val one=envelope("m1",1)
        val two=envelope("m2",2)
        assertEquals(T043WriteResult.APPLIED,storage.receive(one,raw))
        assertEquals(T043WriteResult.APPLIED,storage.receive(two,raw))
        assertEquals(T043WriteResult.REJECTED,storage.applyInboxAtomically("sender-1","m2"))
        assertEquals(T043WriteResult.APPLIED,storage.applyInboxAtomically("sender-1","m1"))
        assertEquals(T043WriteResult.ALREADY_SEEN,storage.applyInboxAtomically("sender-1","m1"))
        assertEquals(T043WriteResult.APPLIED,storage.applyInboxAtomically("sender-1","m2"))
        assertEquals("2",queryString(
            "SELECT last_contiguous_no FROM t043_sync_cursors WHERE direction='INBOUND'"))
        val claimed=envelope("m3",3,"poker.verified-hand.v1")
        assertEquals(T043WriteResult.APPLIED,storage.receive(claimed,handBytes()))
        assertEquals(T043WriteResult.REJECTED,storage.applyInboxAtomically("sender-1","m3"))
        assertEquals(0,count("t043_verified_hands"))
        assertEquals(0,count("t043_verified_events"))
        assertEquals("RECEIVED",queryString(
            "SELECT processing_state FROM t043_sync_inbox WHERE message_id='m3'"))
    }

    @Test fun corruptedStoredObservationIsNotConsideredDuplicate() {
        val bytes=obsBytes()
        assertEquals(T043WriteResult.APPLIED,storage.importObservation(bytes))
        helper.writableDatabase.execSQL(
            "UPDATE t043_observations SET raw_sha256=?",
            arrayOf("f".repeat(64)))
        assertEquals(T043WriteResult.STORAGE_ERROR,storage.importObservation(bytes))
        assertEquals(0,count("t043_conflicts"))
    }

    @Test fun corruptedStoredOutboxCannotMasqueradeAsDuplicate() {
        val draft=T043SyncDraft("sender","id1","receiver","stream","diagnostic")
        val bytes="""{"a":1}""".toByteArray(StandardCharsets.UTF_8)
        assertEquals(T043WriteResult.APPLIED,storage.enqueue(draft,bytes))
        val db=helper.writableDatabase
        // Simulate on-disk corruption in a disposable test DB only.
        db.execSQL("DROP TRIGGER t043_outbox_immutable")
        db.execSQL("UPDATE t043_sync_outbox SET raw_sha256=?",arrayOf("f".repeat(64)))
        assertEquals(T043WriteResult.STORAGE_ERROR,storage.enqueue(draft,bytes))
        assertEquals(1,count("t043_sync_outbox"))
    }

    @Test fun inboxRejectsModifiedStoredRawDigestOnDuplicate() {
        val header=envelope("m1",1)
        val raw=obsBytes()
        assertEquals(T043WriteResult.APPLIED,storage.receive(header,raw))
        helper.writableDatabase.execSQL("DROP TRIGGER t043_inbox_immutable")
        helper.writableDatabase.execSQL(
            "UPDATE t043_sync_inbox SET raw_sha256=?",arrayOf("f".repeat(64)))
        assertEquals(T043WriteResult.STORAGE_ERROR,storage.receive(header,raw))
    }

    @Test fun inboundApplyAfterResetKeepsTerminalAndNoDelete() {
        val raw=obsBytes()
        assertEquals(T043WriteResult.APPLIED,storage.receive(envelope("m1",1),raw))
        assertEquals(T043WriteResult.APPLIED,storage.applyInboxAtomically("sender-1","m1"))
        val db=helper.writableDatabase
        try {
            db.execSQL("UPDATE t043_sync_inbox SET processing_state='REJECTED' WHERE message_id='m1'")
            fail("APPLIED state regressed")
        } catch (_: android.database.SQLException) {}
        try {
            db.execSQL("DELETE FROM t043_sync_inbox WHERE message_id='m1'")
            fail("APPLIED message deleted")
        } catch (_: android.database.SQLException) {}
        helper.close()
        helper=AndroidHandLearningStore(context)
        storage=T043CanonicalSqliteStore(helper)
        assertEquals(T043WriteResult.ALREADY_SEEN,storage.applyInboxAtomically("sender-1","m1"))
        assertEquals("1",queryString(
            "SELECT last_contiguous_no FROM t043_sync_cursors WHERE direction='INBOUND'"))
    }
}
