import pathlib,re,unittest
ROOT=pathlib.Path(__file__).resolve().parents[1]
PKG=ROOT/"android/app/src/main/java/com/example/airemote"

class CornerSuitSamplesRegression(unittest.TestCase):
    def test_independent_8_samples_card_corners(self):
        code=(PKG/"VerifiedCardCornerSuitSamples.kt").read_text()
        rows=re.findall(r"^[hdcs]\|[0-9a-f]{144}$",code,re.M)
        self.assertEqual(len(rows),8)
        self.assertIn("val x1=card.left+card.width*40/100",code)
        self.assertIn("val y0=card.top+card.height*27/100",code)
        self.assertIn("first.value<=0.075",code)
        self.assertIn("second-first.value>=0.07",code)
        self.assertIn("winner.size<55",code)
    def test_fail_closed_on_disagreement(self):
        src=(PKG/"CardVisionCore.kt").read_text()
        self.assertIn("VerifiedCardCornerSuitSamples.classify(r, card)",src)
        self.assertIn("legacy.suit != small.suit -> null",src)
        self.assertIn("VerifiedCardRankSamples.classify(r, card)",src)
        self.assertIn("legacy.rank != verified.rank -> null",src)
    def test_stop_and_two_frame_consensus_retained(self):
        source=(PKG/"CaptureService.kt").read_text()
        self.assertIn("FrameStore.cardConsensus.accept(",source)
        self.assertIn("OperatorStopStore.isStopped(",source)
        code=(PKG/"VerifiedCardCornerSuitSamples.kt").read_text()
        for forbidden in ("dispatchGesture(", "requestVerifiedAction(", "ChatGptCloudBridge"):
            self.assertNotIn(forbidden,code)

if __name__=="__main__":unittest.main()
