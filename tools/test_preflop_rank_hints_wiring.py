import pathlib
import unittest
P=pathlib.Path(__file__).resolve().parents[1]/"android/app/src/main/java/com/example/airemote"
class PreflopRankHintWiring(unittest.TestCase):
    def test_card_capture_drives_gate_with_board_geometry(self):
        service=(P/"CaptureService.kt").read_text()
        self.assertIn("preflopRankGate.offer(",service)
        self.assertIn("visual.visibleBoardSlots, visual.miniRanks",service)
        self.assertIn("atCaptureHint",service)
    def test_ocr_requires_both_same_and_current_stage(self):
        ocr=(P/"OnDeviceAutomaticOcr.kt").read_text()
        self.assertIn("atCapture?.allowed == true",ocr)
        self.assertIn("currentHint?.allowed == true",ocr)
        self.assertIn("PreflopBottomRankText.parse(",ocr)
    def test_bottom_text_never_authorizes_actions(self):
        src=(P/"PreflopRankHints.kt").read_text()
        for forbidden in ("dispatchGesture(", "requestVerifiedAction(", "ChatGptCloudBridge.askPrompt("):
            self.assertNotIn(forbidden,src)
    def test_minis_are_display_only(self):
        read=(P/"OnDeviceCardReader.kt").read_text()
        self.assertIn("MiniHeroRankReader.read(adaptiveRaster)",read)
        overlay=(P/"VisibleCardOverlayText.kt").read_text()
        self.assertIn("МИНИ: ",overlay)
        self.assertIn("miniRankHints",overlay)
    def test_preflop_text_display_must_match_hero_minis(self):
        s=(P/"VisibleCardOverlayText.kt").read_text()
        self.assertIn('currentPreflopHint?.allowed == true',s)
        self.assertIn('reading.miniRankHints == hint.miniRanks',s)
        self.assertIn('ДО ФЛОПА: ',s)

if __name__=="__main__":unittest.main()
