import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT/'android/app/src/main/java/com/example/airemote/MainActivity.kt'
SERVICE = ROOT/'android/app/src/main/java/com/example/airemote/CaptureService.kt'
MODEL = ROOT/'android/app/src/main/java/com/example/airemote/HandStateTracker.kt'

class HandTrackerIntegrationChecks(unittest.TestCase):
    def test_real_tracker_module_in_android_sources(self):
        self.assertTrue(MODEL.exists())
        model = MODEL.read_text()
        for stage in ('WAITING', 'PREFLOP', 'FLOP', 'TURN', 'RIVER', 'SHOWDOWN', 'FINISHED'):
            self.assertIn(stage, model)

    def test_no_unverified_frame_claims(self):
        model = MODEL.read_text()
        self.assertIn('sourceVerified', model)
        self.assertIn('maximumConfirmationGapMs', model)
        self.assertIn('minimumConfidence', model)
        self.assertIn('maximumFrameAgeMs', model)
        self.assertIn('Неоднозначные карты или фаза раздачи', model)

    def test_ui_uses_tracker_as_unconfirmed_until_connected(self):
        activity = MAIN.read_text()
        self.assertIn('OnDeviceHandState.snapshot(now)', activity)
        self.assertIn('Фаза раздачи: НЕ ПОДТВЕРЖДЕНА', activity)
        self.assertNotIn('OnDeviceHandState.tracker.ingest(', activity)

    def test_stop_and_capture_end_invalidate_context(self):
        activity = MAIN.read_text()
        service = SERVICE.read_text()
        self.assertGreaterEqual(activity.count('OnDeviceHandState.reset('), 3)
        self.assertIn('OnDeviceHandState.reset("Захват завершён")', service)

    def test_no_added_network_or_game_actions(self):
        manifest = (ROOT/'android/app/src/main/AndroidManifest.xml').read_text()
        self.assertIn('android.permission.INTERNET', manifest)
        self.assertIn('android.permission.BIND_ACCESSIBILITY_SERVICE', manifest)
        self.assertNotIn('<uses-permission android:name="android.permission.BIND_ACCESSIBILITY_SERVICE"', manifest)

if __name__ == '__main__': unittest.main()
