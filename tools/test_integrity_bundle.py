from pathlib import Path
import unittest

P = Path(__file__).resolve().parents[1]
SRC = P / 'android/app/src/main/java/com/example/airemote'

class IntegrityBundleChecks(unittest.TestCase):
    def test_capture_links_stage_bridge_without_inventing_source(self):
        t = (SRC/'CaptureService.kt').read_text()
        self.assertIn('OnDeviceStageBridge.engine.observe(', t)
        self.assertIn('stable, null, captureEpoch', t)
    def test_operator_stopping_resets_bridge(self):
        t=(SRC/'MainActivity.kt').read_text()
        self.assertIn('OnDeviceStageBridge.reset("Аварийный STOP")', t)
        self.assertIn('OnDeviceStageBridge.reset("Новый захват")', t)
        s=(SRC/'CaptureService.kt').read_text()
        self.assertIn('OnDeviceStageBridge.reset("Захват завершён")',s)
    def test_readiness_blocks_unverified_sources(self):
        t=(SRC/'MainActivity.kt').read_text()
        self.assertIn('appVerified = false',t)
        self.assertIn('tableVerified = false',t)
        self.assertIn('strategyConnected = OnDeviceAutonomousRuntime.strategyInstalled()',t)
        self.assertIn('exactActionLabelsConfirmed = false',t)
        self.assertIn('GameReadinessStatus.inspect(',t)
    def test_accessibility_interrupt_disarms(self):
        s=(SRC/'LiveGestureAccessibilityService.kt').read_text()
        self.assertIn('override fun onInterrupt() {',s)
        self.assertIn('OnDeviceLiveActionControl.stop()',s)
    def test_no_apk_from_bundle(self):
        self.assertFalse(any(P.glob('**/*.apk')))
