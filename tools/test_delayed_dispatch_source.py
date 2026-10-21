"""Structural tests for final Android gesture revalidation; no real gestures."""
import pathlib
import unittest
BASE = pathlib.Path(__file__).parents[1]
SRC = BASE / 'android/app/src/main/java/com/example/airemote'

class TestDelayedDispatch(unittest.TestCase):
    def test_pure_guard_exists(self):
        self.assertTrue((SRC/'LiveDispatchFreshness.kt').exists())
    def test_preflight_called_twice(self):
        text=(SRC/'LiveGestureAccessibilityService.kt').read_text()
        self.assertGreaterEqual(text.count('LiveDispatchFreshness.verify('), 2)
    def test_requires_active_capture_epoch(self):
        text=(SRC/'LiveGestureAccessibilityService.kt').read_text()
        self.assertIn('FrameStore.captureEpoch, FrameStore.isCapturing',text)
    def test_requires_fresh_root_not_fallback(self):
        text=(SRC/'LiveGestureAccessibilityService.kt').read_text()
        self.assertIn('LiveForegroundProbe.recordRoot(rootPackage, now)', text)
        self.assertNotIn('?: e.packageName', text)
        self.assertIn('rootInActiveWindow?.packageName?.toString(), LiveGamePermissionGate.packageName()',text)
    def test_observation_source_kept(self):
        text=(SRC/'LiveGestureAccessibilityService.kt').read_text()
        self.assertIn('nowMs, view.capturedAtMs',text)
        self.assertIn('sourceCapturedAtMs: Long = createdMs',(SRC/'LiveGameActionGuard.kt').read_text())
    def test_stop_overlay_area_cannot_receive_game_tap(self):
        text=(SRC/'LiveGestureAccessibilityService.kt').read_text()
        self.assertIn('stopOccludesTarget',text)
    def test_debug_version_updated(self):
        self.assertRegex((BASE/'android/app/build.gradle.kts').read_text(), r'versionCode = (?:4[3-9]|[5-9][0-9])')
        self.assertIn('AI Remote Control v${packageManager.getPackageInfo(packageName, 0).versionName}', (SRC/'MainActivity.kt').read_text())

if __name__ == '__main__': unittest.main()
