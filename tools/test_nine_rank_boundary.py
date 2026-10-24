import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
SRC = ROOT / "android/app/src/main/java/com/example/airemote"

class NineRankRegression(unittest.TestCase):
    def test_real_nine_mask_and_strict_margin(self):
        f=(SRC/"VerifiedCardRankSamples.kt").read_text()
        samples=[x for x in f.splitlines() if x.startswith("9|") and len(x)==146]
        self.assertGreaterEqual(len(samples),4)
        test=(ROOT/"tools/NineRankBoundaryScenarios.kt").read_text()
        self.assertIn("00000001ff0003ff8007c780",test)
        self.assertIn("VerifiedCardRankSamples.classifyMask(rows)?.rank == '9'",test)
        self.assertIn("VerifiedCardRankSamples.sampleSanityChecks()",test)
        self.assertIn("runnerUp-first.value>=minimumSeparation",f)
    def test_unrelated_recognizers_not_relaxed(self):
        s=(SRC/"CardVisionCore.kt").read_text()
        self.assertIn("first.second >= 0.78",s)
        self.assertIn("first.second - second >= 0.045",s)
        self.assertIn("legacy.rank != verified.rank -> null",s)
        self.assertIn("winner.value >= 0.78",s)
    def test_real_jvm_suite_executes_regression(self):
        runner=(ROOT/"tools/run_jvm_tests.sh").read_text()
        self.assertIn("com.example.airemote.NineRankBoundaryScenarios",runner)

if __name__=="__main__": unittest.main()
