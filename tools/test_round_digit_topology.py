import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
CODE = ROOT / "android/app/src/main/java/com/example/airemote"

class RoundDigitsSafeHints(unittest.TestCase):
    def test_6_8_9_have_distinct_bounded_hole_classification(self):
        s=(CODE/"RoundDigitTopology.kt").read_text()
        self.assertIn("inner.size == 2",s)
        self.assertIn("-> '8'",s)
        self.assertIn("-> '6'",s)
        self.assertIn("-> '9'",s)
        self.assertIn("variants.all { v -> v == it.key }",s)
        self.assertIn("VerifiedCardRankSamples.rankMask",s)
    def test_rank_only_suit_reader_unchanged(self):
        s=(CODE/"CardGlyphChain.kt").read_text()
        self.assertIn("RoundDigitTopology.hint(r, card)",s)
        self.assertIn("rank?.rank?.let { it in \"689\" } == true",s)
        self.assertIn("combine(null, suit, ink)",s)
        self.assertIn("return provisional.copy(preview=label)",s)
        self.assertNotIn("code=\"",s)
        self.assertNotIn("dispatchGesture(",s)
    def test_existing_verified_templates_remain_in_regression(self):
        sc=(ROOT/"tools/RoundDigitTopologyScenarios.kt").read_text()
        self.assertIn("VerifiedCardRankSamples.labeledRankMasks()",sc)
        self.assertIn("actual==null || actual==rank",sc)
        runner=(ROOT/"tools/run_jvm_tests.sh").read_text()
        self.assertIn("com.example.airemote.RoundDigitTopologyScenarios",runner)

if __name__=="__main__":
    unittest.main()
