import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / 'android/app/src/main/java/com/example/airemote'

class ForegroundCaptureIntegrationTests(unittest.TestCase):
    def test_activity_visibility_transitions(self):
        source = (BASE / 'MainActivity.kt').read_text()
        self.assertIn('override fun onStart()', source)
        self.assertIn('AppVisibilityGate.onActivityVisible()', source)
        self.assertIn('override fun onStop()', source)
        self.assertIn('AppVisibilityGate.onActivityHidden()', source)

    def test_capture_rejects_self_ui_and_stale_session(self):
        source = (BASE / 'CaptureService.kt').read_text()
        self.assertIn('AppVisibilityGate.acquireTicket()', source)
        self.assertIn('AppVisibilityGate.canPublish(visibilityTicket)', source)
        self.assertIn('captureEpoch == FrameStore.captureEpoch', source)
        self.assertIn('!OperatorStopStore.isStopped(applicationContext)', source)

    def test_frame_health_not_published_without_frame(self):
        source = (BASE / 'CaptureService.kt').read_text()
        self.assertIn('FrameStore.localFrameHealth = frameHealth', source)
        self.assertEqual(source.count('FrameStore.localFrameHealth ='), 1)

    def test_status_does_not_claim_known_game_origin(self):
        source = (BASE / 'MainActivity.kt').read_text()
        self.assertIn('приложение-источник не подтверждено', source)

    def test_insets_include_display_cutout(self):
        source = (BASE / 'MainActivity.kt').read_text()
        self.assertIn('WindowInsets.Type.displayCutout()', source)

if __name__ == '__main__':
    unittest.main()
