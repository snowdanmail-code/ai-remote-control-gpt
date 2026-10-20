import pathlib
import unittest

BASE = pathlib.Path(__file__).resolve().parent.parent / "android/app/src/main/java/com/example/airemote"

class WindowOriginSourceTests(unittest.TestCase):
    def test_root_is_not_guessed_from_event(self):
        src = (BASE / "LiveGestureAccessibilityService.kt").read_text()
        self.assertNotIn("?: e.packageName", src)
        self.assertIn("rootInActiveWindow?.packageName", src)
        self.assertIn("main.postDelayed(this, 350L)", src)
        self.assertIn("main.removeCallbacks(rootSampler)", src)
        self.assertIn("LiveForegroundProbe.noteWindowTransition()", src)

    def test_capture_requires_stable_source_before_decoding(self):
        src = (BASE / "CaptureService.kt").read_text()
        self.assertIn("windowTracker.observe(CapturedWindowFrame", src)
        self.assertIn("if (origin.safeForLocalDiagnostics && frameHealth.usable", src)
        self.assertIn("windowStillMatches(origin, rootEvidence)", src)
        self.assertIn("FrameStore.observeWindow(captureEpoch, origin)", src)
        self.assertIn("invalidateVisualEvidence()", (BASE / "FrameStore.kt").read_text())
        self.assertIn("lastSignature = null", src)
        self.assertIn("WindowOriginTracker(applicationContext.packageName)", src)

    def test_not_granted_game_permission(self):
        src = (BASE / "CaptureService.kt").read_text()
        self.assertIn("sourceVerified", (BASE / "CardToHandStageBridge.kt").read_text())
        self.assertIn("stable, null, captureEpoch", src)
        self.assertIn('TurnVisualSignal(\n                                            now, captureEpoch, cardInspectionSeq, false', src)
        self.assertNotIn("LiveGamePermissionGate.grantForVerifiedEnvironment", src)

    def test_readiness_is_still_blocked(self):
        src = (BASE / "MainActivity.kt").read_text()
        self.assertIn('appVerified = false', src)
        self.assertIn('tableVerified = false', src)
        self.assertIn('"$originText', src)

if __name__ == '__main__': unittest.main()
