import pathlib
import unittest

ROOT=pathlib.Path(__file__).resolve().parents[1]
SRC=ROOT/"android/app/src/main/java/com/example/airemote"

class VisibleCardOverlaySourceTests(unittest.TestCase):
    def test_accessibility_connection_and_no_new_permission(self):
        service=(SRC/"LiveGestureAccessibilityService.kt").read_text()
        self.assertIn("VisibleCardOverlayController(this)",service)
        self.assertIn("VisibleCardOverlayText.project(",service)
        self.assertIn("signal.reading, FrameStore.confirmedCards",service)
        self.assertIn("cardOverlay.hide()",service)
        self.assertIn("cardOverlay.dispose()",service)
        manifest=(ROOT/"android/app/src/main/AndroidManifest.xml").read_text()
        self.assertNotIn("SYSTEM_ALERT_WINDOW",manifest)
    def test_two_transparent_noninteractive_windows(self):
        text=(SRC/"VisibleCardOverlayController.kt").read_text()
        for word in ("TYPE_ACCESSIBILITY_OVERLAY","FLAG_NOT_TOUCHABLE",
                     "PixelFormat.TRANSLUCENT","0.295f","0.635f",
                     "СТОЛ · ","МОИ КАРТЫ · ","View.GONE","Color.argb(139"):
            self.assertIn(word,text)
    def test_model_never_performs_game_actions(self):
        for path in ("VisibleCardOverlayText.kt","VisibleCardOverlayController.kt"):
            src=(SRC/path).read_text()
            for forbidden in ("dispatchGesture(","requestVerifiedAction(",
                              "ChatGptCloudBridge.askPrompt("):
                self.assertNotIn(forbidden,src)
    def test_captured_game_foreground_scoped(self):
        src=(SRC/"LiveGestureAccessibilityService.kt").read_text()
        self.assertIn("FrameStore.lastCards",src)
        self.assertIn("foreground.packageName == FrameStore.windowOrigin?.observedPackage",src)
        self.assertIn("!OperatorStopStore.isStopped(applicationContext)",src)

if __name__=="__main__":
    unittest.main()
