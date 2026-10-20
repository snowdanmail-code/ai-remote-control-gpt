import re
"""Source integration regressions for strictly diagnostic seat OCR evidence."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / 'android/app/src/main/java/com/example/airemote'

class SeatVisualSourceTests(unittest.TestCase):
    def test_no_implicit_layout_or_gameplay_authorization(self):
        src = (APP / 'SeatVisualDiagnostics.kt').read_text()
        self.assertIn('private var calibration: SeatLayoutCalibration? = null', src)
        self.assertIn('SeatDiagnosticStatus.NOT_CALIBRATED', src)
        self.assertIn('SeatDiagnosticStatus.STABLE_DIAGNOSTIC', src)
        for disallowed in ('dispatchGesture(', 'ConfirmedOpponentAction(',
                           'grantForVerifiedEnvironment(', 'commitHandAtomically('):
            self.assertNotIn(disallowed, src)

    def test_frame_provenance_and_layout_binding(self):
        src = (APP / 'SeatVisualDiagnostics.kt').read_text()
        for key in ('captureEpoch', 'windowGeneration', 'sourcePackage',
                    'frameWidth', 'frameHeight', 'minimumGapMs', 'maximumGapMs',
                    'maxAgeMs', 'reset()', 'line.box.left > it.bounds.left'):
            self.assertIn(key, src)

    def test_worker_uses_ocr_but_does_not_convert_to_confirmed_action(self):
        s = (APP / 'OnDeviceAutomaticOcr.kt').read_text()
        self.assertIn('OnDeviceSeatVisualDiagnostics.inspect(', s)
        self.assertIn('snapshot.lines', s)
        self.assertIn('FrameStore.lastSeatVisual =', s)
        self.assertIn('OnDeviceSeatVisualDiagnostics.reset()', s)
        self.assertNotIn('ingestConfirmedAction(', s)

    def test_reset_uses_atomic_frame_store(self):
        s = (APP / 'FrameStore.kt').read_text()
        self.assertIn('lastSeatVisual = null', s)
        self.assertIn('OnDeviceSeatVisualDiagnostics.reset()', s)
        self.assertIn('@Synchronized fun invalidateVisualEvidence()', s)

    def test_ui_shows_unverified_state(self):
        s=(APP / 'MainActivity.kt').read_text()
        self.assertIn('ДИАГНОСТИКА МЕСТ ИГРОКОВ', s)
        self.assertIn('FrameStore.lastSeatVisual?.summary()', s)
        self.assertIn('Автоматические касания запрещены', s)

    def test_jvm_runner_includes_scenarios(self):
        self.assertIn('com.example.airemote.SeatVisualScenarios',
                      (ROOT/'tools/run_jvm_tests.sh').read_text())
        self.assertTrue((ROOT/'tools/SeatVisualScenarios.kt').exists())

    def test_version_increment_and_previous_qa_present(self):
        self.assertGreaterEqual(int(re.search(r"versionCode\s*=\s*(\d+)", (ROOT/'android/app/build.gradle.kts').read_text()).group(1)), 60)
        self.assertTrue((ROOT/'QA_v2.14.23.json').exists())

if __name__ == '__main__':
    unittest.main()
