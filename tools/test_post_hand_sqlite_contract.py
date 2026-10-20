"""Pure SQLite verification of DDL and the per-hand upsert SQL embedded in Android code.
No poker game simulator, no server, no network.
"""
import pathlib
import re
import sqlite3
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
SRC = (ROOT/'android/app/src/main/java/com/example/airemote/AndroidHandLearningStore.kt').read_text()

class HandSqliteContract(unittest.TestCase):
    def setUp(self):
        self.db=sqlite3.connect(':memory:')
        ddl=re.findall(r'db\.execSQL\("""(CREATE TABLE.*?)"""\)',SRC,re.S)
        self.assertEqual(len(ddl),4)
        for sql in ddl:self.db.execute(sql)

    def tearDown(self): self.db.close()

    def test_tables(self):
        names={row[0] for row in self.db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        self.assertTrue({'completed_hands','opponents','hand_opponent_facts','hand_hero_actions'}<=names)

    def test_receipt_unique(self):
        data=('table-a','hand-a',1,999,50.0)
        self.db.execute('INSERT INTO completed_hands VALUES(?,?,?,?,?)',data)
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute('INSERT INTO completed_hands VALUES(?,?,?,?,?)',data)

    def test_one_hero_decision_per_id(self):
        row=('table-a','hand-a','action1','FLOP','CALL',3,5.0)
        self.db.execute('INSERT INTO hand_hero_actions VALUES(?,?,?,?,?,?,?)',row)
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute('INSERT INTO hand_hero_actions VALUES(?,?,?,?,?,?,?)',row)

    def test_profile_delta_query_and_counts(self):
        sql=re.search(r'db\.execSQL\("""(UPDATE opponents SET.*?)""", arrayOf\(',SRC,re.S).group(1)
        self.db.execute('INSERT INTO opponents(opponent_key) VALUES(?)',('player1',))
        # One observed opponent: vpip 1/1, PFR 0/1, raises 2, calls 1, fold 0/1,
        # showdown 1 with loss.
        delta=(1,1,0,1,2,1,0,1,1,0,'player1')
        self.db.execute(sql,delta)
        values=self.db.execute('SELECT * FROM opponents WHERE opponent_key=?',('player1',)).fetchone()
        self.assertEqual(values,('player1',1,1,1,0,1,2,1,0,1,1,0))

    def test_txn_atomic_rollback(self):
        try:
            self.db.execute('BEGIN')
            self.db.execute('INSERT INTO completed_hands VALUES(?,?,?,?,?)',('table-a','hand-a',1,999,50))
            self.db.execute('INSERT INTO hand_hero_actions VALUES(?,?,?,?,?,?,?)',('table-a','hand-a','a','FLOP','CALL',3,5))
            self.db.execute('INSERT INTO hand_hero_actions VALUES(?,?,?,?,?,?,?)',('table-a','hand-a','a','FLOP','CALL',3,5))
            self.db.execute('COMMIT')
        except sqlite3.IntegrityError:
            self.db.execute('ROLLBACK')
        self.assertEqual(self.db.execute('SELECT COUNT(*) FROM completed_hands').fetchone()[0],0)

if __name__=='__main__': unittest.main()
