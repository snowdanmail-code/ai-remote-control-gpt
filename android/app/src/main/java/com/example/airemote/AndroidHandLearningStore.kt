package com.example.airemote

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** On-phone, persistent post-hand learning. No network/Windows/Python dependency. */
class AndroidHandLearningStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext, "hand_learning_v1.db", null, 1
), HandLearningStore {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE completed_hands (
            table_key TEXT NOT NULL, hand_key TEXT NOT NULL,
            capture_epoch INTEGER NOT NULL, finished_ms INTEGER NOT NULL,
            hero_net REAL,
            PRIMARY KEY(table_key, hand_key))""")
        db.execSQL("""CREATE TABLE opponents (
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
        db.execSQL("""CREATE TABLE hand_hero_actions (
            table_key TEXT NOT NULL, hand_key TEXT NOT NULL,
            action_key TEXT NOT NULL, street TEXT NOT NULL, action TEXT NOT NULL,
            frame_seq INTEGER NOT NULL, amount_chips REAL,
            PRIMARY KEY(table_key, hand_key, action_key))""")
        db.execSQL("""CREATE TABLE hand_opponent_facts (
            table_key TEXT NOT NULL, hand_key TEXT NOT NULL,
            opponent_key TEXT NOT NULL, vpip INTEGER, pfr INTEGER,
            aggression INTEGER, calls INTEGER, faced_bet INTEGER,
            fold_vs_bet INTEGER, showdown INTEGER, showdown_won INTEGER,
            PRIMARY KEY(table_key, hand_key, opponent_key))""")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Future schema migrations are additive; never silently erase learned profiles.
        throw IllegalStateException("No migration from $oldVersion to $newVersion")
    }

    @Synchronized override fun commitHandAtomically(hand: VerifiedFinishedHand): HandLearningResult {
        val db = writableDatabase
        db.beginTransaction()
        return try {
            val exists = db.rawQuery(
                "SELECT 1 FROM completed_hands WHERE table_key=? AND hand_key=? LIMIT 1",
                arrayOf(hand.tableKey, hand.handKey)
            ).use { it.moveToFirst() }
            if (exists) {
                db.setTransactionSuccessful()
                HandLearningResult.ALREADY_SEEN
            } else {
                db.execSQL("INSERT INTO completed_hands VALUES(?,?,?,?,?)", arrayOf(
                    hand.tableKey, hand.handKey, hand.captureEpoch, hand.finishedAtElapsedMs,
                    hand.heroNetChips
                ))
                hand.verifiedHeroActions.forEach { action ->
                    db.execSQL("INSERT INTO hand_hero_actions VALUES(?,?,?,?,?,?,?)", arrayOf(
                        hand.tableKey, hand.handKey, action.actionKey,
                        action.street, action.action, action.frameSequence, action.amountChips
                    ))
                }
                hand.opponents.forEach { facts ->
                    // Never merge anonymous/uncertain seat identity across different hands.
                    if (!facts.identityVerified) return@forEach
                    fun b(value: Boolean?): Int? = value?.let { if (it) 1 else 0 }
                    db.execSQL("INSERT INTO hand_opponent_facts VALUES(?,?,?,?,?,?,?,?,?,?,?)", arrayOf(
                        hand.tableKey, hand.handKey, facts.opponentKey,
                        b(facts.vpip), b(facts.preflopRaise), facts.postflopBetsOrRaises,
                        facts.postflopCalls, b(facts.facedBet), b(facts.foldedWhenFacingBet),
                        b(facts.showdown), b(facts.showdownWon)
                    ))
                    db.execSQL("INSERT OR IGNORE INTO opponents(opponent_key) VALUES(?)", arrayOf(facts.opponentKey))
                    db.execSQL("""UPDATE opponents SET
                        observed_hands=observed_hands+1,
                        vpip_seen=vpip_seen+?, vpip_opportunities=vpip_opportunities+?,
                        pfr_seen=pfr_seen+?, pfr_opportunities=pfr_opportunities+?,
                        aggression_actions=aggression_actions+?, postflop_calls=postflop_calls+?,
                        folds_facing_bet=folds_facing_bet+?, faced_bet_opportunities=faced_bet_opportunities+?,
                        showdowns_seen=showdowns_seen+?, showdowns_won=showdowns_won+?
                        WHERE opponent_key=?""", arrayOf(
                            if (facts.vpip == true) 1 else 0, if (facts.vpip != null) 1 else 0,
                            if (facts.preflopRaise == true) 1 else 0, if (facts.preflopRaise != null) 1 else 0,
                            facts.postflopBetsOrRaises ?: 0, facts.postflopCalls ?: 0,
                            if (facts.foldedWhenFacingBet == true) 1 else 0,
                            // Unknown fold outcomes cannot count as confirmed opportunities.
                            if (facts.foldedWhenFacingBet != null) 1 else 0,
                            if (facts.showdown == true) 1 else 0,
                            if (facts.showdownWon == true) 1 else 0,
                            facts.opponentKey
                        ))
                }
                db.setTransactionSuccessful()
                HandLearningResult.APPLIED
            }
        } catch (_: Exception) {
            HandLearningResult.STORAGE_ERROR
        } finally { db.endTransaction() }
    }

    @Synchronized override fun opponentProfile(opponentKey: String): OpponentLearningSummary? =
        readableDatabase.rawQuery("SELECT * FROM opponents WHERE opponent_key=?", arrayOf(opponentKey)).use { c ->
            if (!c.moveToFirst()) null else OpponentLearningSummary(
                opponentKey, c.getInt(1), c.getInt(2), c.getInt(3), c.getInt(4), c.getInt(5),
                c.getInt(6), c.getInt(7), c.getInt(8), c.getInt(9), c.getInt(10), c.getInt(11)
            )
        }

    @Synchronized override fun recentHandOutcomes(limit: Int): List<String> {
        val bounded = limit.coerceIn(0, 10)
        val results = mutableListOf<String>()
        readableDatabase.rawQuery(
            "SELECT table_key, hand_key, hero_net FROM completed_hands ORDER BY rowid DESC LIMIT $bounded",
            emptyArray()
        ).use { c ->
            while (c.moveToNext()) {
                val net = if (c.isNull(2)) "итог не наблюдался" else "изменение фишек ${c.getDouble(2)}"
                val moves = readableDatabase.rawQuery(
                    "SELECT GROUP_CONCAT(street || ':' || action, ', ') FROM " +
                        "(SELECT street, action FROM hand_hero_actions WHERE table_key=? AND hand_key=? ORDER BY frame_seq)",
                    arrayOf(c.getString(0), c.getString(1))
                ).use { m -> if (m.moveToFirst() && !m.isNull(0)) m.getString(0) else "нет проверенных действий" }
                results.add("Раздача ${c.getString(1)} (${c.getString(0)}): $net; действия: $moves")
            }
        }
        return results
    }

    @Synchronized override fun completedHandsCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM completed_hands", emptyArray()).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
}

/** Explicit API for the future VERIFIED end-of-hand event detector. Never infer victory from cards. */
object OnDevicePostHandLearning {
    @Volatile private var learner: PostHandLearner? = null
    @Synchronized fun initialize(context: Context) {
        if (learner == null) learner = PostHandLearner(AndroidHandLearningStore(context))
    }
    fun onVerifiedHandFinished(event: VerifiedFinishedHand): HandLearningResult =
        learner?.onHandFinished(event) ?: HandLearningResult.STORAGE_ERROR
    fun profileForNextDecision(opponentKey: String): OpponentLearningSummary? =
        learner?.profileForNextDecision(opponentKey)
    fun contextForNextDecision(opponentsAtTable: List<String>): String =
        learner?.contextForNextDecision(opponentsAtTable) ?: "История раздач ещё не загружена"
}


/**
 * A companion transient observer, not a second database or an alternative
 * learning path. Only independent source/identity verification may feed it.
 * The read-only strategy context combines CURRENT confirmed observations with
 * earlier atomically committed, finished hands. Nothing here dispatches taps.
 */
object OnDeviceOpponentAwareness {
    private val observer = LiveOpponentAwareness()

    @Synchronized fun beginVerifiedHand(start: VerifiedOpponentHandStart): OpponentEvidenceResult =
        observer.begin(start)

    @Synchronized fun ingestConfirmedAction(context: Context,
        action: ConfirmedOpponentAction): OpponentEvidenceResult {
        val result = if (OperatorStopStore.isStopped(context)) {
            observer.reset()
            OpponentEvidenceResult.INVALIDATED
        } else observer.observe(action)
        // Allow-listed fields ONLY. Never record opponentKey/actionKey/tableKey/handKey.
        if (result == OpponentEvidenceResult.REJECTED || result == OpponentEvidenceResult.INVALIDATED)
            OnDeviceIncidentRecorder.record(context,
                if (result == OpponentEvidenceResult.INVALIDATED)
                    IncidentCode.OPPONENT_CONTEXT_INVALIDATED else IncidentCode.OPPONENT_OBSERVATION_REJECTED,
                IncidentSeverity.WARNING, action.captureEpoch, action.confirmedFrame,
                action = action.action, stage = action.street)
        return result
    }

    @Synchronized fun contextForDecision(): String =
        observer.contextForDecision { verifiedKeys ->
            OnDevicePostHandLearning.contextForNextDecision(verifiedKeys)
        }

    @Synchronized fun proposeVerifiedTerminal(frame: VerifiedHandBoundaryFrame): VerifiedFinishedHand? =
        observer.proposalFromTrustedTerminal(frame)

    @Synchronized fun resetTransientOnly() { observer.reset() }
}

/**
 * Prepared connection to the durable learner. NO live producer is connected:
 * CaptureService currently cannot prove foreground identity, stable hand ID or
 * positive terminal cues. Calling this with unverified observations is forbidden.
 */
object OnDeviceVerifiedHandCompletion {
    @Volatile private var gate: VerifiedHandCompletionGate? = null
    @Volatile private var applicationContext: Context? = null

    @Synchronized fun initialize(context: Context) {
        val app = context.applicationContext
        OnDevicePostHandLearning.initialize(app)
        applicationContext = app
        if (gate == null) gate = VerifiedHandCompletionGate(saveFinishedHand = { hand ->
            OnDevicePostHandLearning.onVerifiedHandFinished(hand)
        })
    }

    @Synchronized fun ingestTrustedObservation(frame: VerifiedHandBoundaryFrame,
        facts: VerifiedFinishedHand? = null): HandBoundaryDecision {
        val context = applicationContext
        val live = gate
        if (context == null || live == null) return HandBoundaryDecision(
            HandBoundaryStatus.STORAGE_ERROR, "Модуль не инициализирован")
        if (OperatorStopStore.isStopped(context)) {
            live.reset()
            OnDeviceOpponentAwareness.resetTransientOnly()
            return HandBoundaryDecision(HandBoundaryStatus.STOPPED, "Аварийный STOP")
        }
        // The observer never bypasses the verified completion gate. It only
        // attaches positive facts when a trusted caller supplied a real hand ID
        // AND window generation. Currently no live producer can assert these.
        val proposed = facts ?: OnDeviceOpponentAwareness.proposeVerifiedTerminal(frame)
        val decision = live.ingest(frame, proposed)
        if (decision.status == HandBoundaryStatus.STORAGE_ERROR) {
            OnDeviceIncidentRecorder.record(context, IncidentCode.OPPONENT_HAND_STORAGE_FAILED,
                IncidentSeverity.ERROR, frame.captureEpoch, frame.frameSequence)
        }
        if (decision.status == HandBoundaryStatus.COMMITTED ||
            decision.status == HandBoundaryStatus.ALREADY_COMMITTED) {
            OnDeviceOpponentAwareness.resetTransientOnly()
        } else if (decision.status == HandBoundaryStatus.REJECTED &&
            (!frame.sourceVerified || !frame.stableHandKeyVerified)) {
            OnDeviceOpponentAwareness.resetTransientOnly()
        }
        return decision
    }

    @Synchronized fun resetTransientEvidence() {
        gate?.reset()
        OnDeviceOpponentAwareness.resetTransientOnly()
    }
}
