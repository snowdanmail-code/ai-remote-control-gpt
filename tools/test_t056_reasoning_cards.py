"""T056 source-grounded poker reasoning library; offline advisory only."""
import json
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CARD_FILE = ROOT / "tools" / "t056_poker_reasoning_cards.json"
DOC_FILE = ROOT / "docs" / "T056_ELITE_POKER_INTERVIEWS_REASONING.md"


class PokerInterviewReasoningLibraryTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.library = json.loads(CARD_FILE.read_text(encoding="utf-8"))
        cls.document = DOC_FILE.read_text(encoding="utf-8")

    def test_source_grounded_and_non_exhaustive(self):
        d = self.library
        self.assertEqual("t056.interview-reasoning-cards.v1", d["schema"])
        self.assertEqual("NO_LIMIT_TEXAS_HOLDEM_CASH", d["applicableTo"])
        self.assertTrue(d["replayOnly"])
        self.assertTrue(d["notModelTraining"])
        self.assertTrue(d["notGtoOracle"])
        self.assertIn("Невозможно достоверно прочесть", self.document)
        self.assertIn("НЕ копировать личность", self.document)
        self.assertEqual(12, len(d["cards"]))

    def test_cards_come_from_attributed_sources_and_never_allow_taps(self):
        used_ids = set()
        for card in self.library["cards"]:
            self.assertRegex(card["id"], r"^T056-\d{2}$")
            self.assertNotIn(card["id"], used_ids)
            used_ids.add(card["id"])
            self.assertEqual("NLHE_CASH_REPLAY_ONLY", card["scope"])
            self.assertFalse(card["authorizesLiveAction"])
            self.assertEqual("synthesis_not_verbatim", card["derived"])
            self.assertGreaterEqual(len(card["question"]), 20)
            self.assertGreaterEqual(len(card["avoidInference"]), 20)
            self.assertTrue(card["failClosedReason"])
            self.assertTrue(card["requiresVerified"])
            self.assertTrue(card["sourceIds"])
            for source in card["sourceIds"]:
                self.assertRegex(source, r"^[A-Z]\d+$")
                self.assertRegex(
                    self.document, r"\| " + re.escape(source) + r" \|")
        self.assertEqual(len(used_ids), 12)

    def test_source_ledger_links_and_domain_boundaries(self):
        source_ids = sorted({
            sid for card in self.library["cards"] for sid in card["sourceIds"]
        })
        for source in source_ids:
            self.assertIn("| " + source + " |", self.document)
        self.assertGreaterEqual(self.document.count("https://"), 12)
        for restriction in (
            "ICM", "Cash 6-max", "NO_RECOMMENDATION", "STOP",
            "не подмешиваются", "не означает, что GPT",
        ):
            self.assertIn(restriction, self.document)
        self.assertNotIn("authorizesLiveAction\": true", CARD_FILE.read_text())

    def test_no_unconditional_bet_prescriptions(self):
        flat = json.dumps(self.library, ensure_ascii=False).lower()
        self.assertNotIn("always raise", flat)
        self.assertNotIn("always bluff", flat)
        self.assertNotIn("bet at all costs", flat)
        self.assertNotIn("all_in_now", flat)


if __name__ == "__main__":
    unittest.main()
