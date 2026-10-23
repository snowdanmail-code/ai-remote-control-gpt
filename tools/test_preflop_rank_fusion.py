import pathlib
import unittest

ROOT=pathlib.Path(__file__).resolve().parents[1]
P=ROOT/"android/app/src/main/java/com/example/airemote"
class RankThresholdAndFusionSourceTests(unittest.TestCase):
    def test_rank_threshold_lowered_by_15_percentage_points(self):
        source=(P/"CardVisionCore.kt").read_text()
        self.assertIn("first.second >= 0.78",source)
        self.assertIn("first.second - second >= 0.045",source)
        self.assertNotIn("first.second >= 0.93",source)
        self.assertIn("legacy.rank != verified.rank -> null",source)
        # Suit thresholds must not change in this task.
        self.assertIn("winner.value >= 0.78",source)
    def test_only_display_uses_three_sources(self):
        app=(P/"VisibleCardOverlayText.kt").read_text()
        self.assertIn("PreflopRankFusion.preview(reading, reading.miniRankHints,",app)
        fusion=(P/"PreflopRankFusion.kt").read_text()
        self.assertIn("visual.indices.any",fusion)
        self.assertIn("reading.community.isEmpty()",fusion)
        self.assertIn("safeCaption",fusion)
        self.assertNotIn("cardConsensus.accept(",fusion)
        self.assertNotIn("dispatchGesture(",fusion)
    def test_gate_still_closes_after_flop(self):
        gate=(P/"PreflopRankHints.kt").read_text()
        self.assertIn("if(boardCount in 3..5)",gate)
        self.assertIn("if(!postFlopSeen)return true",gate)
        self.assertIn("if(candidateFrames<2)return false",gate)
        ocr=(P/"OnDeviceAutomaticOcr.kt").read_text()
        self.assertIn("val samePreflop",ocr)
        self.assertIn("PreflopBottomRankText.parse(snapshot.lines, samePreflop)",ocr)
    def test_no_unverified_hand_from_hint(self):
        fusion=(P/"PreflopRankFusion.kt").read_text()
        self.assertNotIn("CardReading(",fusion)
        self.assertNotIn("FrameStore.confirmedCards =",fusion)
        self.assertNotIn("LocalPlayerCheckPilot",fusion)

if __name__=="__main__": unittest.main()
