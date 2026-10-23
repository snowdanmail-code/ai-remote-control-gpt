import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
SRC=ROOT/"android/app/src/main/java/com/example/airemote"

class NineRankRescueContract(unittest.TestCase):
    def test_fallback_runs_only_after_ordinary_rank_readers_fail(self):
        f=(SRC/"CardVisionCore.kt").read_text()
        self.assertIn("legacy != null && verified != null && legacy.rank != verified.rank -> null",f)
        self.assertIn("verified != null -> verified",f)
        self.assertIn("else -> VerifiedCardRankSamples.recoverTrimmedNine(r, card)",f)
    def test_no_new_suit_or_cross_rank_relaxation(self):
        s=(SRC/"VerifiedCardRankSamples.kt").read_text()
        self.assertIn("val nines = candidates.filter { it.rank == '9' }",s)
        self.assertIn("if (nines.size < 2 || candidates.any { it.rank != '9' })",s)
        self.assertIn("card.left - shift",s)
        self.assertIn("(1..4)",s)
        self.assertIn("first.key == '5'",s)
        c=(SRC/"CardVisionCore.kt").read_text()
        self.assertIn("first.second >= 0.78",c)
        self.assertIn("winner.value >= 0.78",c)
    def test_replay_mask_exists_in_real_jvm_runner(self):
        suite=(ROOT/"tools/NineRankBoundaryScenarios.kt").read_text()
        self.assertIn("CardVisionCore.classifyRank(raster, shifted)",suite)
        runner=(ROOT/"tools/run_jvm_tests.sh").read_text()
        self.assertIn("com.example.airemote.NineRankBoundaryScenarios",runner)

if __name__=="__main__":
    unittest.main()
