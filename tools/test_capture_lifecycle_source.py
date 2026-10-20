import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parent.parent
BASE = ROOT / "android/app/src/main/java/com/example/airemote"

class CaptureLifecycleSourceTests(unittest.TestCase):
    def test_stopping_state_denies_restart(self):
        source = (BASE / "CaptureLifecycleGuard.kt").read_text()
        self.assertIn("phase != CaptureLifecycle.IDLE", source)
        self.assertIn("phase = CaptureLifecycle.STOPPING", source)
        self.assertIn("fun completeStop()", source)

    def test_service_reserves_and_releases_gate(self):
        source = (BASE / "CaptureService.kt").read_text()
        self.assertIn("gate.beginStart()", source)
        self.assertIn("gate.markActive()", source)
        self.assertIn("gate.completeStop()", source)
        self.assertIn("OnDeviceLiveActionControl.stop()", source)
        self.assertIn("stopSelfResult(startId)", source)

    def test_ui_refuses_concurrent_request(self):
        source = (BASE / "MainActivity.kt").read_text()
        self.assertGreaterEqual(source.count("gate.mayRequestNewCapture()"), 2)
        after = source.split("override fun onActivityResult", 1)[1].split("override fun onRequestPermissionsResult", 1)[0]
        self.assertNotIn("\n        stopService(", after)

if __name__ == "__main__": unittest.main()
