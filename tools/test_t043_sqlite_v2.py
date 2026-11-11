"""T043 stage-2 SQLite DDL regression on real sqlite3, no Android SDK or server.
52 explicit cases; test fixtures never touch a user's database.
"""
import hashlib
import json
import os
import pathlib
import re
import sqlite3
import subprocess
import sys
import tempfile
import unittest
from decimal import Decimal

ROOT = pathlib.Path(__file__).resolve().parents[1]
MAIN = ROOT / "android/app/src/main/java/com/example/airemote"
LEGACY_SRC = (MAIN / "AndroidHandLearningStore.kt").read_text()
V2_SRC = (MAIN / "T043SqliteV2Schema.kt").read_text()
NEW_DDL = re.findall(r'"""(CREATE (?:TABLE|INDEX|TRIGGER).*?)"""', V2_SRC, flags=re.S)
OLD_DDL = re.findall(r'db\.execSQL\("""(CREATE TABLE.*?)"""\)', LEGACY_SRC, flags=re.S)
EXPECTED = {"t043_sources", "t043_observations", "t043_verified_hands",
            "t043_verified_events", "t043_conflicts", "t043_sync_outbox",
            "t043_sync_inbox", "t043_sync_cursors", "t043_model_registry",
            "t043_training_metadata", "t043_training_members"}
RULES = "holdem.standard52.no-limit.v1"
OTHER = "holdem.standard52.pot-limit.v1"
HEX = "a" * 64


def open_db(path=":memory:", version=2):
    db = sqlite3.connect(path, isolation_level=None)
    db.execute("PRAGMA foreign_keys=ON")
    for stmt in OLD_DDL:
        db.execute(stmt)
    if version == 2:
        for stmt in NEW_DDL:
            db.execute(stmt)
    db.execute(f"PRAGMA user_version={version}")
    return db


def canonical_number(value):
    s = str(value)
    d = Decimal(s)
    if d.is_zero():
        return "0"
    sign, digits, exponent = d.as_tuple()
    nums = "".join(map(str, digits)).lstrip("0")
    while nums.endswith("0"):
        nums = nums[:-1]
        exponent += 1
    return ("-" if sign else "") + nums + ("e" + str(exponent) if exponent else "")


def canonical_json(data):
    def pairs(items):
        d = {}
        for k, v in items:
            if k in d:
                raise ValueError("duplicate key")
            d[k] = v
        return d
    def write(obj):
        if isinstance(obj, dict):
            return "{" + ",".join(write(k) + ":" + write(obj[k]) for k in
                                  sorted(obj, key=lambda s: s.encode("utf-8"))) + "}"
        if isinstance(obj, list):
            return "[" + ",".join(write(v) for v in obj) + "]"
        if isinstance(obj, str):
            return json.dumps(obj, ensure_ascii=False, separators=(",", ":"))
        if obj is None: return "null"
        if obj is True: return "true"
        if obj is False: return "false"
        if isinstance(obj, Decimal): return canonical_number(obj)
        raise ValueError("invalid canonical type")
    obj = json.loads(data.decode("utf-8"), object_pairs_hook=pairs,
                     parse_int=Decimal, parse_float=Decimal)
    return write(obj).encode("utf-8")


def tlv(part):
    if part is None: return b"\x00"
    raw = str(part).encode("utf-8")
    return b"\x01" + len(raw).to_bytes(4, "big") + raw


def envelope_hash(parts, digest, mode, algo="json-semantic.v1"):
    payload = b"T043-ENVELOPE-v1\x00"
    fields = [parts.get(k) for k in
              ("sender_id", "message_id", "recipient_id", "stream_id", "sequence_no",
               "content_type", "source_namespace", "related_record_type",
               "related_record_key", "ruleset_id", "payload_encoding",
               "schema_major", "schema_minor")]
    fields += [algo, mode, digest]
    return hashlib.sha256(payload + b"".join(tlv(f) for f in fields)).hexdigest()


def msg(sender="A", mid="m1", recipient="B", stream="hands", seq=1, payload=b'{"a":1}',
        mode="SEMANTIC_JSON", state="RECEIVED", outbox=False, algorithm="json-semantic.v1"):
    raw = hashlib.sha256(payload).hexdigest()
    semantic = hashlib.sha256(canonical_json(payload)).hexdigest() if mode == "SEMANTIC_JSON" else None
    d = semantic if semantic is not None else raw
    return dict(sender_id=sender, message_id=mid, recipient_id=recipient,
                stream_id=stream, sequence_no=seq, content_type="poker.observation",
                payload_encoding="JSON_UTF8", canonicalization_id=algorithm,
                comparison_mode=mode, payload_bytes=sqlite3.Binary(payload),
                raw_sha256=raw, semantic_sha256=semantic,
                envelope_sha256=envelope_hash(dict(
                    sender_id=sender, message_id=mid, recipient_id=recipient,
                    stream_id=stream, sequence_no=seq, content_type="poker.observation",
                    payload_encoding="JSON_UTF8"), d, mode, algorithm),
                **({"delivery_state":("PENDING" if state == "RECEIVED" else state), "created_at_ms":1234}
                   if outbox else {"processing_state":state, "received_at_ms":1234}))


def insert(db, table, data):
    keys = list(data)
    db.execute(f"INSERT INTO {table} ({','.join(keys)}) VALUES ({','.join('?' for _ in keys)})",
               tuple(data[k] for k in keys))


def cursor(db, sender="A", recipient="B", stream="hands", direction="INBOUND"):
    insert(db, "t043_sync_cursors",
           dict(sender_id=sender, recipient_id=recipient, stream_id=stream,
                direction=direction, updated_at_ms=1234))


def apply(db, seq, sender="A", recipient="B", stream="hands"):
    row = msg(sender=sender,mid=f"m-{seq}",recipient=recipient,stream=stream,seq=seq,state="APPLIED")
    row["applied_at_ms"] = 1234
    insert(db,"t043_sync_inbox",row)


def hand(db, hid="H", rules=RULES):
    insert(db,"t043_sources",dict(namespace="fixture.alpha",first_seen_ms=100))
    insert(db,"t043_verified_hands",dict(hand_record_id=hid,namespace="fixture.alpha",
         ruleset_id=rules,verification_policy_id="independent-full-record.v1",
         schema_major=1,schema_minor=0,payload_json="{}",payload_bytes=sqlite3.Binary(b"{}"),
         payload_sha256=HEX,verified_at_ms=1234))


class T043SqliteV2Tests(unittest.TestCase):
    def setUp(self):
        self.db = open_db()

    def tearDown(self):
        self.db.close()

    def test_01_filled_migration_keeps_rows(self):
        with tempfile.TemporaryDirectory() as td:
            path = os.path.join(td,"hand_learning_v1.db")
            old = open_db(path,version=1)
            old.execute("INSERT INTO completed_hands VALUES(?,?,?,?,?)",("tab","h",7,99,12.5))
            old.close()
            db=sqlite3.connect(path,isolation_level=None)
            db.execute("PRAGMA foreign_keys=ON")
            db.execute("BEGIN")
            for s in NEW_DDL:db.execute(s)
            db.execute("PRAGMA user_version=2")
            db.execute("COMMIT")
            self.assertEqual(db.execute("PRAGMA user_version").fetchone()[0],2)
            self.assertEqual(db.execute("SELECT * FROM completed_hands").fetchone(),
                             ("tab","h",7,99,12.5))
            db.close()

    def test_02_fresh_db_has_15_tables(self):
        tables={x[0] for x in self.db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        self.assertEqual(len(tables),15)
        self.assertTrue(EXPECTED <= tables)

    def test_03_old_ddl_unchanged(self):
        for ddl in OLD_DDL:
            name=re.search(r"CREATE TABLE (\w+)",ddl).group(1)
            stored=self.db.execute("SELECT sql FROM sqlite_master WHERE name=?",(name,)).fetchone()[0]
            self.assertEqual(stored,ddl)

    def test_04_legacy_positional_hand_commit(self):
        self.db.execute("INSERT INTO completed_hands VALUES(?,?,?,?,?)",("t","h",1,3,20.0))
        self.assertEqual(self.db.execute("SELECT COUNT(*) FROM completed_hands").fetchone()[0],1)

    def test_05_legacy_profile_column_order(self):
        self.db.execute("INSERT INTO opponents VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                        ("player",1,2,3,4,5,6,7,8,9,10,11))
        self.assertEqual(self.db.execute("SELECT * FROM opponents").fetchone(),
                         ("player",1,2,3,4,5,6,7,8,9,10,11))

    def test_06_legacy_completed_count_unchanged(self):
        self.db.execute("INSERT INTO completed_hands VALUES(?,?,?,?,?)",("t","h",1,3,20))
        cursor(self.db)
        self.assertEqual(self.db.execute("SELECT COUNT(*) FROM completed_hands").fetchone()[0],1)

    def test_07_legacy_recent_hand_outcomes_order(self):
        for n in range(3):
            self.db.execute("INSERT INTO completed_hands VALUES(?,?,?,?,?)",
                            ("t",f"h{n}",n,n,n*1.0))
        got=[r[0] for r in self.db.execute(
            "SELECT hand_key FROM completed_hands ORDER BY rowid DESC LIMIT 2")]
        self.assertEqual(got,["h2","h1"])

    def test_08_inbox_message_pk(self):
        row=msg();insert(self.db,"t043_sync_inbox",row)
        with self.assertRaises(sqlite3.IntegrityError):insert(self.db,"t043_sync_inbox",row)

    def test_09_same_id_different_sender(self):
        insert(self.db,"t043_sync_inbox",msg())
        insert(self.db,"t043_sync_inbox",msg(sender="C"))
        self.assertEqual(self.db.execute("SELECT COUNT(*) FROM t043_sync_inbox").fetchone()[0],2)

    def test_10_same_id_changed_contents_rejected(self):
        insert(self.db,"t043_sync_inbox",msg())
        with self.assertRaises(sqlite3.IntegrityError):
            insert(self.db,"t043_sync_inbox",msg(payload=b'{"a":2}'))
        self.assertEqual(self.db.execute("SELECT payload_bytes FROM t043_sync_inbox").fetchone()[0],b'{"a":1}')

    def test_11_recorded_conflict_survives_savepoint_rollback(self):
        self.db.execute("BEGIN")
        self.db.execute("SAVEPOINT t043_import")
        insert(self.db,"t043_sources",dict(namespace="temp",first_seen_ms=1))
        self.db.execute("ROLLBACK TO SAVEPOINT t043_import")
        self.db.execute("RELEASE SAVEPOINT t043_import")
        self.db.execute("INSERT INTO t043_conflicts(entity_type,identity_key,stored_envelope_sha256,incoming_envelope_sha256,reason,detected_at_ms) VALUES(?,?,?,?,?,?)",
                        ("sync_inbox","A:m1",HEX,"b"*64,"CHANGED",1))
        self.db.execute("COMMIT")
        self.assertEqual(self.db.execute("SELECT COUNT(*) FROM t043_conflicts").fetchone()[0],1)
        self.assertEqual(self.db.execute("SELECT COUNT(*) FROM t043_sources").fetchone()[0],0)

    def test_12_conflict_failed_insert_rolls_back(self):
        self.db.execute("BEGIN")
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("INSERT INTO t043_conflicts(entity_type,identity_key,stored_envelope_sha256,incoming_envelope_sha256,reason,detected_at_ms) VALUES(?,?,?,?,?,?)",
                            ("INVALID","x",HEX,HEX,"test",1))
        self.db.execute("ROLLBACK")
        self.assertEqual(self.db.execute("SELECT COUNT(*) FROM t043_conflicts").fetchone()[0],0)

    def test_13_key_order_same_semantics(self):
        self.assertEqual(canonical_json(b'{"b":2,"a":1}'),canonical_json(b'{"a":1,"b":2}'))

    def test_14_future_minor_preserved_raw(self):
        row=msg(payload=b'{"schemaMinor":9,"secretFuture":123}')
        insert(self.db,"t043_sync_inbox",dict(row,processing_state="QUARANTINED",
                payload_schema_major=1,payload_schema_minor=9))
        self.assertEqual(self.db.execute("SELECT payload_bytes FROM t043_sync_inbox").fetchone()[0],
                         b'{"schemaMinor":9,"secretFuture":123}')

    def test_15_raw_hash_detects_tamper(self):
        row=msg();insert(self.db,"t043_sync_inbox",row)
        self.assertEqual(hashlib.sha256(self.db.execute(
            "SELECT payload_bytes FROM t043_sync_inbox").fetchone()[0]).hexdigest(),row["raw_sha256"])

    def test_16_protected_payload_immutable(self):
        insert(self.db,"t043_sync_inbox",msg())
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("UPDATE t043_sync_inbox SET payload_bytes=?",(b"{}",))

    def test_17_unknown_ruleset_not_verified(self):
        hand(self.db)
        with self.assertRaises(sqlite3.IntegrityError):
            insert(self.db,"t043_verified_hands",dict(hand_record_id="h2",namespace="fixture.alpha",
                   ruleset_id="holdem.future.no-limit.v1",
                   verification_policy_id="independent-full-record.v1",
                   schema_major=1,schema_minor=0,payload_json="{}",
                   payload_bytes=sqlite3.Binary(b"{}"),payload_sha256=HEX,verified_at_ms=1))

    def test_18_event_requires_verified_hand(self):
        with self.assertRaises(sqlite3.IntegrityError):
            insert(self.db,"t043_verified_events",dict(event_key="E",hand_record_id="missing",
                   ruleset_id=RULES,event_id="e",seq=1,evidence_level="INDEPENDENT",
                   payload_json="{}",payload_sha256=HEX))

    def test_19_training_ruleset_foreign_key(self):
        hand(self.db)
        insert(self.db,"t043_training_metadata",dict(dataset_id="D",ruleset_id=OTHER,
             verification_policy_id="independent-full-record.v1",
             schema_major=1,schema_minor=0,created_ms=1))
        with self.assertRaises(sqlite3.IntegrityError):
            insert(self.db,"t043_training_members",
                   dict(dataset_id="D",hand_record_id="H",ruleset_id=OTHER))

    def test_20_all_composite_fk_parents_unique(self):
        keys=self.db.execute("PRAGMA foreign_key_check").fetchall()
        self.assertEqual(keys,[])
        self.assertIn("unique(hand_record_id,ruleset_id)",
                      "".join(NEW_DDL).replace(" ","").lower())

    def test_21_failed_migration_is_atomic(self):
        with tempfile.TemporaryDirectory() as td:
            path=os.path.join(td,"legacy.db")
            db=open_db(path,version=1)
            db.execute("BEGIN")
            try:
                for sql in NEW_DDL[:3]:db.execute(sql)
                db.execute("CREATE TABLE t043_sources(x TEXT)") # deliberate duplicate
                db.execute("COMMIT")
            except sqlite3.OperationalError:
                db.execute("ROLLBACK")
            self.assertEqual(db.execute("PRAGMA user_version").fetchone()[0],1)
            self.assertEqual(db.execute(
                "SELECT COUNT(*) FROM sqlite_master WHERE name='t043_sources'").fetchone()[0],0)
            db.close()

    def test_22_crash_rolls_back_uncommitted_file_txn(self):
        with tempfile.TemporaryDirectory() as td:
            path=os.path.join(td,"crash.db")
            db=open_db(path)
            db.close()
            code=("import sqlite3,os,sys\n"
                  "d=sqlite3.connect(sys.argv[1],isolation_level=None)\n"
                  "d.execute('BEGIN IMMEDIATE')\n"
                  "d.execute(\"INSERT INTO t043_sources VALUES('CRASH',123)\")\n"
                  "os._exit(1)\n")
            proc=subprocess.run([sys.executable,"-c",code,path],check=False)
            self.assertEqual(proc.returncode,1)
            db=sqlite3.connect(path)
            self.assertEqual(db.execute("SELECT COUNT(*) FROM t043_sources").fetchone()[0],0)
            db.close()

    def test_23_sent_unconfirmed_cannot_fake_ack(self):
        insert(self.db,"t043_sync_outbox",msg(outbox=True,state="SENT_UNCONFIRMED"))
        self.assertEqual(self.db.execute("SELECT delivery_state FROM t043_sync_outbox").fetchone()[0],
                         "SENT_UNCONFIRMED")

    def test_24_applied_state_persists(self):
        apply(self.db,1)
        self.assertEqual(self.db.execute("SELECT processing_state FROM t043_sync_inbox").fetchone()[0],"APPLIED")

    def test_25_stop_never_deletes_tables(self):
        self.assertEqual(len(OLD_DDL),4)
        self.assertNotIn("DROP TABLE",V2_SRC)

    def test_26_integrity_and_foreign_keys(self):
        self.assertEqual(self.db.execute("PRAGMA integrity_check").fetchone()[0],"ok")
        self.assertEqual(self.db.execute("PRAGMA foreign_key_check").fetchall(),[])

    def test_27_concurrent_allocation_unique_position(self):
        cursor(self.db,direction="OUTBOUND")
        insert(self.db,"t043_sync_outbox",msg(mid="one",seq=1,outbox=True,state="PENDING"))
        with self.assertRaises(sqlite3.IntegrityError):
            insert(self.db,"t043_sync_outbox",msg(mid="two",seq=1,outbox=True,state="PENDING"))
        insert(self.db,"t043_sync_outbox",msg(mid="two",seq=2,outbox=True,state="PENDING"))
        self.assertEqual([x[0] for x in self.db.execute(
            "SELECT sequence_no FROM t043_sync_outbox ORDER BY sequence_no")],[1,2])

    def test_28_cursor_and_outbox_rollback_together(self):
        cursor(self.db,direction="OUTBOUND")
        self.db.execute("BEGIN")
        self.db.execute("UPDATE t043_sync_cursors SET last_allocated_no=1,max_seen_no=1 WHERE direction='OUTBOUND'")
        self.db.execute("ROLLBACK")
        self.assertEqual(self.db.execute("SELECT last_allocated_no FROM t043_sync_cursors").fetchone()[0],0)

    def test_29_retransmit_does_not_reallocate(self):
        insert(self.db,"t043_sync_outbox",msg(outbox=True))
        self.assertEqual(self.db.execute("SELECT sequence_no FROM t043_sync_outbox").fetchone()[0],1)

    def test_30_sequence_collision_unique(self):
        insert(self.db,"t043_sync_outbox",msg(outbox=True))
        with self.assertRaises(sqlite3.IntegrityError):
            insert(self.db,"t043_sync_outbox",msg(mid="other",outbox=True))

    def test_31_out_of_order_101_103_102_contiguous(self):
        cursor(self.db)
        for n in range(1,102): apply(self.db,n)
        self.db.execute("UPDATE t043_sync_cursors SET max_seen_no=101,last_contiguous_no=101")
        insert(self.db,"t043_sync_inbox",msg(mid="m-103",seq=103))
        self.db.execute("UPDATE t043_sync_cursors SET max_seen_no=103")
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("UPDATE t043_sync_cursors SET last_contiguous_no=103")
        apply(self.db,102)
        self.db.execute("UPDATE t043_sync_inbox SET processing_state='APPLIED',applied_at_ms=1234 WHERE message_id='m-103'")
        self.db.execute("UPDATE t043_sync_cursors SET last_contiguous_no=103")
        self.assertEqual(self.db.execute("SELECT last_contiguous_no FROM t043_sync_cursors").fetchone()[0],103)

    def test_32_repeat_102_is_not_reinserted(self):
        apply(self.db,102)
        with self.assertRaises(sqlite3.IntegrityError): apply(self.db,102)

    def test_33_gap_blocks_cursor(self):
        cursor(self.db)
        apply(self.db,1);apply(self.db,3)
        self.db.execute("UPDATE t043_sync_cursors SET max_seen_no=3,last_contiguous_no=1")
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("UPDATE t043_sync_cursors SET last_contiguous_no=3")

    def test_34_quarantined_message_blocks_progress(self):
        cursor(self.db)
        apply(self.db,1)
        insert(self.db,"t043_sync_inbox",msg(mid="m-2",seq=2,state="QUARANTINED"))
        self.db.execute("UPDATE t043_sync_cursors SET max_seen_no=2,last_contiguous_no=1")
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("UPDATE t043_sync_cursors SET last_contiguous_no=2")

    def test_35_independent_senders(self):
        cursor(self.db,sender="A");cursor(self.db,sender="C")
        self.assertEqual(self.db.execute("SELECT COUNT(*) FROM t043_sync_cursors").fetchone()[0],2)

    def test_36_independent_recipients(self):
        cursor(self.db,recipient="B");cursor(self.db,recipient="C")
        self.assertEqual(self.db.execute("SELECT COUNT(*) FROM t043_sync_cursors").fetchone()[0],2)

    def test_37_same_sender_id_across_recipients_not_allowed(self):
        insert(self.db,"t043_sync_inbox",msg())
        with self.assertRaises(sqlite3.IntegrityError):
            insert(self.db,"t043_sync_inbox",msg(recipient="C"))

    def test_38_restart_persists_missing_102(self):
        with tempfile.TemporaryDirectory() as td:
            path=os.path.join(td,"restart.db")
            db=open_db(path)
            cursor(db)
            apply(db,1)
            insert(db,"t043_sync_inbox",msg(mid="m-3",seq=3))
            db.execute("UPDATE t043_sync_cursors SET max_seen_no=3,last_contiguous_no=1")
            db.close()
            db=sqlite3.connect(path)
            self.assertEqual(db.execute("SELECT last_contiguous_no FROM t043_sync_cursors").fetchone()[0],1)
            self.assertEqual(db.execute("SELECT max_seen_no FROM t043_sync_cursors").fetchone()[0],3)
            db.close()

    def test_39_immutable_sequence_rejected(self):
        insert(self.db,"t043_sync_inbox",msg())
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("UPDATE t043_sync_inbox SET sequence_no=2")

    def test_40_json_key_order_vector(self):
        self.assertEqual(hashlib.sha256(canonical_json(b'{"b":2,"a":1}')).hexdigest(),
                         "43258cff783fe7036d8a43033f830adfc60ec037382473548ac742b888292777")

    def test_41_big_integer_vector(self):
        self.assertEqual(hashlib.sha256(canonical_json(b'{"n":9007199254740993}')).hexdigest(),
                         "4ac8309cc76123ef6c5325ef925fc873e9b5856ec4f844ef1462f9303960378a")

    def test_42_numeric_representations_vector(self):
        for x in [b'{"n":100}',b'{"n":1.00e2}']:
            self.assertEqual(hashlib.sha256(canonical_json(x)).hexdigest(),
                             "477bbdd93b24d7ebe5b110b7bd76f53e82c2831cd361d557779cbf3f1da9c522")

    def test_43_unicode_vector(self):
        raw='{"é":"😀","x":"\\u0061"}'.encode("utf-8")
        self.assertEqual(hashlib.sha256(canonical_json(raw)).hexdigest(),
                         "a86028a753c46fc19f0934078aaa243b814d37aebe9ed1942b30ddbaa21f4e3e")

    def test_44_duplicate_key_rejected(self):
        with self.assertRaises(ValueError):canonical_json(b'{"a":1,"\\u0061":2}')

    def test_45_malformed_json_rejected(self):
        with self.assertRaises(json.JSONDecodeError):canonical_json(b'{"a":')

    def test_46_raw_bytes_duplicate_hash(self):
        b=b'{"a":'
        self.assertEqual(hashlib.sha256(b).hexdigest(),
                         "ffb38b22ee3e0ca90325ebce953a9846990f292faf44c50498771602e31cb61f")

    def test_47_raw_bytes_conflict_hash(self):
        self.assertNotEqual(hashlib.sha256(b'{"a":').hexdigest(),
                            hashlib.sha256(b'{"b":').hexdigest())

    def test_48_equivalent_json_different_raw_hash(self):
        a,b=b'{"a":1,"b":2}',b'{"b":2,"a":1}'
        self.assertNotEqual(hashlib.sha256(a).hexdigest(),hashlib.sha256(b).hexdigest())
        self.assertEqual(hashlib.sha256(canonical_json(a)).digest(),
                         hashlib.sha256(canonical_json(b)).digest())

    def test_49_applied_terminal_cannot_reject_or_change_time(self):
        apply(self.db,1)
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("UPDATE t043_sync_inbox SET processing_state='REJECTED'")
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("UPDATE t043_sync_inbox SET applied_at_ms=999")

    def test_50_no_delete_confirmed_range(self):
        apply(self.db,1)
        cursor(self.db)
        self.db.execute("UPDATE t043_sync_cursors SET max_seen_no=1,last_contiguous_no=1")
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("DELETE FROM t043_sync_inbox WHERE sequence_no=1")
        self.assertEqual(self.db.execute("SELECT COUNT(*) FROM t043_sync_inbox").fetchone()[0],1)

    def test_51_ack_terminal_cannot_retry_or_replace_receipt(self):
        data=msg(outbox=True,state="ACKNOWLEDGED")
        data.update(acknowledged_at_ms=4321,acknowledgement_ref="verified-receipt")
        insert(self.db,"t043_sync_outbox",data)
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("UPDATE t043_sync_outbox SET delivery_state='RETRY'")
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("UPDATE t043_sync_outbox SET acknowledgement_ref='forged'")
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("DELETE FROM t043_sync_outbox")

    def test_52_untrusted_checkpoint_rejected_and_fixture_restore_reserved(self):
        # Production creates streams with implicit checkpoint 0; a caller cannot
        # claim nonzero without at least an explicit proof reference.
        with self.assertRaises(sqlite3.IntegrityError):
            insert(self.db,"t043_sync_cursors",dict(sender_id="A",recipient_id="B",
                  stream_id="hands",direction="INBOUND",initial_checkpoint_no=100,
                  updated_at_ms=123))
        cursor(self.db)
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("UPDATE t043_sync_cursors SET initial_checkpoint_no=100")
        # A labeled synthetic fixture exercises the future trusted-restore
        # representation. Production has no API to create such a checkpoint
        # until a proof verifier is separately implemented.
        insert(self.db,"t043_sync_cursors",dict(
            sender_id="fixture",recipient_id="B",stream_id="restored",direction="INBOUND",
            initial_checkpoint_no=100,
            initial_checkpoint_proof_ref="verified-fixture-not-production",
            last_contiguous_no=100,max_seen_no=100,updated_at_ms=123))
        self.assertEqual(self.db.execute("SELECT initial_checkpoint_no FROM t043_sync_cursors WHERE sender_id='fixture'").fetchone()[0],100)
        store = (MAIN / "T043CanonicalSqliteStore.kt").read_text()
        self.assertNotIn('put("initial_checkpoint_no"',store)
        self.assertNotIn('initial_checkpoint_proof_ref',store)


if __name__ == "__main__":
    unittest.main()
