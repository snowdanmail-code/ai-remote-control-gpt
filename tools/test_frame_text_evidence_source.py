import re
import unittest
from pathlib import Path

BASE = Path(__file__).resolve().parents[1]
ROOT = BASE / 'android/app/src/main/java/com/example/airemote'

class OcrEvidenceIntegrationSourceTest(unittest.TestCase):
    def test_offline_ocr_supplies_positioned_lines(self):
        ocr = (ROOT / 'OnDeviceTextRecognition.kt').read_text()
        self.assertIn('line.boundingBox', ocr)
        self.assertIn('PositionedOcrLine(line.text.trim(), norm)', ocr)
        self.assertIn('inspectStructured', ocr)
        self.assertIn('FrameTextEvidence.parse(lines)', ocr)

    def test_read_only_diagnostic_cannot_turn_into_permission(self):
        parser = (ROOT / 'FrameTextEvidence.kt').read_text()
        self.assertNotIn('LiveGamePermissionGate', parser)
        self.assertNotIn('dispatchGesture', parser)
        self.assertNotIn('sourceVerified = true', parser)
        activity = (ROOT / 'MainActivity.kt').read_text()
        self.assertIn('exactActionLabelsConfirmed = false', activity)
        self.assertIn('tableVerified = false', activity)
        self.assertIn('appVerified = false', activity)

    def test_ocr_published_only_for_same_frame_and_epoch(self):
        activity = (ROOT / 'MainActivity.kt').read_text()
        self.assertIn('OcrFrameProvenance.check(ticket, FrameStore.latest', activity)
        self.assertIn('FrameStore.captureEpoch,', activity)
        self.assertIn('FrameStore.windowOrigin, FrameStore.captureEpoch', activity)
        self.assertIn('OcrFrameProvenance.ticket(frame, originAtRequest)', activity)
        self.assertIn('OperatorStopStore.isStopped(this)', activity)
        self.assertIn('result.text = ""', activity)

    def test_coordinates_not_inferred_from_ocr_order(self):
        parser = (ROOT / 'FrameTextEvidence.kt').read_text()
        self.assertIn('centerY in 915..985', parser)
        self.assertIn('centerX in 0..333', parser)
        self.assertIn('centerX in 334..666', parser)
        self.assertIn('centerX in 667..1000', parser)
        self.assertIn('pattern.matches(token)', parser)

    def test_numeric_fail_closed_for_duplicates(self):
        parser = (ROOT / 'FrameTextEvidence.kt').read_text()
        self.assertIn('groupBy { it.kind }.filterValues { it.size == 1 }', parser)
        self.assertIn('toLongOrNull()', parser)
        self.assertIn('kind == NumericCandidateKind.TIMER_SECONDS && value > 120L', parser)

    def test_version_is_unique_and_forward(self):
        gradle = (BASE / 'android/app/build.gradle.kts').read_text()
        activity = (ROOT / 'MainActivity.kt').read_text()
        self.assertGreaterEqual(int(re.search(r"versionCode\s*=\s*(\d+)", gradle).group(1)), 60)
        self.assertRegex(gradle, r'versionName = "2\.14\.\d+[^"]*"')
        self.assertIn('AI Remote Control v${packageManager.getPackageInfo(packageName, 0).versionName}', activity)

if __name__ == '__main__': unittest.main()
