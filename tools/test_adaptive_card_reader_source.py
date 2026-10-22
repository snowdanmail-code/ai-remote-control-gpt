import pathlib
import unittest

ROOT=pathlib.Path(__file__).resolve().parents[1]
PACKAGE=ROOT/"android/app/src/main/java/com/example/airemote"

class CardV2BridgeTest(unittest.TestCase):
    def test_connected_to_existing_player_pipeline(self):
        src=(PACKAGE/"OnDeviceCardReader.kt").read_text()
        capture=(PACKAGE/"CaptureService.kt").read_text()
        self.assertIn("AdaptivePlayerCardReader.read(raster)",src)
        self.assertIn("AdaptiveCardCandidateResolver.choose(original, adaptive)",src)
        self.assertIn("OnDeviceCardReader.inspectAll(screen)",capture)
        self.assertIn("FrameStore.cardConsensus.accept(",capture)
    def test_no_automatic_tap_or_cloud_work(self):
        text=(PACKAGE/"AdaptivePlayerCardReader.kt").read_text()
        self.assertNotIn("dispatchGesture(",text)
        self.assertNotIn("ChatGptCloudBridge",text)
        self.assertNotIn("requestVerifiedAction(",text)
        self.assertIn("candidateHands.size == 1",text)
        self.assertIn("CardVisionCore.checkUnique(hand, community)",text)
    def test_engineering_export_contains_player_quality(self):
        report=(PACKAGE/"SpectatorEngineeringReport.kt").read_text()
        self.assertIn('report.put("player_vision_read_only", PlayerVisionJournal.snapshot(context))',report)
        self.assertIn('player_vision_independent_of_spectator_quality_kpis',report)
        self.assertIn('player_actions_authorized_by_report',report)
    def test_versioned_and_retains_prior_spectator_reader(self):
        source=(PACKAGE/"OnDeviceCardReader.kt").read_text()
        self.assertIn("fun inspectSpectator(source: Bitmap)",source)
        self.assertIn("CardVisionCore.readVisibleCommunity(",source)

if __name__=="__main__":
    unittest.main()
