import pathlib
import unittest
ROOT=pathlib.Path(__file__).resolve().parents[1]
SRC=ROOT/"android/app/src/main/java/com/example/airemote"
class MiniRanksAndPreflopTest(unittest.TestCase):
    def test_rank_crop_not_cut_off_at_one_third_height(self):
        s=(SRC/"PreflopRankHints.kt").read_text()
        self.assertIn("rankBottom=y0+h*48/100",s)
        self.assertIn("glyphLeft=x0+",s)
        self.assertIn("VisionCardBox(0,0,proxyWidth,proxyHeight)",s)
        self.assertIn("isolatedRanks.matches(it)",s)
    def test_live_overlay_marks_mini_hint_as_provisional(self):
        s=(SRC/"VisibleCardOverlayText.kt").read_text()
        self.assertIn("showHeroWithMini(reading)",s)
        self.assertIn("(*МИНИ, НЕ ПОДТВЕРЖДЕНО)",s)
        self.assertIn("reading.hole.getOrNull(index)",s)
    def test_safety_gates_kept(self):
        s=(SRC/"PreflopRankHints.kt").read_text()
        self.assertIn("postFlopSeen=true",s)
        self.assertIn("if(boardCount!=0)return false",s)
        self.assertIn("candidateFrames<2",s)
        capture=(SRC/"CaptureService.kt").read_text()
        self.assertIn("preflopRankGate.offer(",capture)
if __name__=="__main__": unittest.main()
