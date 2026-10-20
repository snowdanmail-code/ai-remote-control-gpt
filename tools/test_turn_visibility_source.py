import unittest
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "android/app/src/main/java/com/example/airemote"
class TurnVisibilityIntegrationChecks(unittest.TestCase):
    def test_visual_detector_stays_read_only(self):
        source = (SRC / "TurnControlsVision.kt").read_text()
        for required in ("TurnDisplayState.PREACTION", "TurnDisplayState.BET_SIZING", "TurnDisplayState.READY", "TurnDisplayState.WAITING", "Неподдерживаемая компоновка"):
            self.assertIn(required, source)
        self.assertNotIn("performClick(", source)
        self.assertNotIn("HttpURLConnection", source)
    def test_no_action_authorization_from_pixels(self):
        source = (SRC / "TurnDisplayConsistency.kt").read_text()
        self.assertIn('authorizedToAct: Boolean get() = false', source)
        self.assertIn('requiredSamples: Int = 2', source)
        self.assertIn('prev.sourceVerified == signal.sourceVerified', source)
    def test_single_raster_used_for_both_detectors(self):
        reader = (SRC / 'OnDeviceCardReader.kt').read_text()
        self.assertIn('data class OnDeviceTableReading', reader)
        self.assertIn('CardVisionCore.readCards(raster)', reader)
        self.assertIn('TurnControlsVision.read(raster)', reader)
        self.assertEqual(reader.count('val pixels = IntArray('), 1)
    def test_capture_gates_and_fail_closed_source(self):
        service = (SRC / 'CaptureService.kt').read_text()
        self.assertIn('OnDeviceCardReader.inspectAll(screen)', service)
        self.assertIn('cardInspectionSeq, false, visual.turn', service)
        self.assertIn('AppVisibilityGate.canPublish(visibilityTicket)', service)
        self.assertIn('OperatorStopStore.isStopped(applicationContext)', service)
    def test_stop_resets_turn_evidence(self):
        store = (SRC / 'FrameStore.kt').read_text()
        self.assertIn('turnConsensus.reset()', store)
        self.assertIn('confirmedTurnVisual = null', store)
    def test_operator_sees_diagnostic_not_button(self):
        ui = (SRC / 'MainActivity.kt').read_text()
        self.assertIn('ПРЕДВАРИТЕЛЬНЫЙ ВЫБОР',ui)
        self.assertIn('ПРИЗНАК НАШЕГО ХОДА',ui)
        self.assertIn('Внешние нажатия заблокированы до проверки условий.',ui)
        self.assertIn('Разработано в лаборатории Сноудена',ui)
    def test_manifest_scoped_accessibility_no_internet(self):
        manifest = (ROOT / 'android/app/src/main/AndroidManifest.xml').read_text()
        self.assertIn('android.permission.INTERNET', manifest)
        self.assertIn('android.permission.BIND_ACCESSIBILITY_SERVICE', manifest)
        self.assertNotIn('<uses-permission android:name="android.permission.BIND_ACCESSIBILITY_SERVICE"', manifest)
    def test_version(self):
        gradle = (ROOT / 'android/app/build.gradle.kts').read_text()
        self.assertGreaterEqual(int(__import__('re').search(r'versionCode = (\d+)', gradle).group(1)), 39)
        self.assertIn('versionName = "2.14.', gradle)
if __name__ == '__main__': unittest.main()
