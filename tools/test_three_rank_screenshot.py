import pathlib,re,unittest
ROOT=pathlib.Path(__file__).resolve().parents[1]
SRC=ROOT/"android/app/src/main/java/com/example/airemote"
class RankThreeScreenTest(unittest.TestCase):
    def test_real_mask_and_narrow_acceptance(self):
        s=(SRC/"VerifiedCardRankSamples.kt").read_text()
        self.assertEqual(len(re.findall(r"^[2-9TJQKA]\|[0-9a-f]{144}$",s,re.M)),30)
        self.assertIn("3|00000007ffe00fffe00fffe00f0fc00e0f80061f00003e0000fe0000ff8000ffc00001e00000e00000f00000f00000f00000f00e00e00f01e007c7c003ff8001ff00007c00000000",s)
        self.assertIn("first.key == '3' && examplesOfRank >= 3",s)
        self.assertIn("first.value <= 0.06 -> 0.055",s)
        self.assertIn("first.key == '5'",s)
    def test_suit_logic_not_changed(self):
        s=(SRC/"CardVisionCore.kt").read_text()
        self.assertIn("legacy.rank != verified.rank -> null",s)
        self.assertIn("winner.value >= 0.78",s)
    def test_jvm_scenarios_wired(self):
        s=(ROOT/"tools/run_jvm_tests.sh").read_text()
        self.assertIn("com.example.airemote.ThreeRankScreenshotScenarios",s)
if __name__=="__main__":unittest.main()
