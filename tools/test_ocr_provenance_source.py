import re
from pathlib import Path
import unittest

BASE = Path(__file__).resolve().parents[1]
ROOT = BASE / 'android/app/src/main/java/com/example/airemote'

class OcrFrameBindingSourceTest(unittest.TestCase):
    def test_window_generation_is_in_origin_assessment(self):
        tracker = (ROOT / 'WindowOriginTracker.kt').read_text()
        self.assertIn('val windowGeneration: Long?', tracker)
        self.assertIn('root.windowGeneration)', tracker)
        self.assertIn('initialRoot.windowGeneration == assessment.windowGeneration', tracker)

    def test_frame_binds_epoch_package_and_generation(self):
        frames = (ROOT / 'FrameStore.kt').read_text()
        service = (ROOT / 'CaptureService.kt').read_text()
        for value in ['val captureEpoch: Long', 'val sourcePackage: String?', 'val windowGeneration: Long?']:
            self.assertIn(value, frames)
        self.assertIn('captureEpoch, origin.observedPackage, origin.windowGeneration)', service)

    def test_ocr_callback_checks_ticket_not_only_package(self):
        activity = (ROOT / 'MainActivity.kt').read_text()
        self.assertIn('OcrFrameProvenance.ticket(frame, originAtRequest)', activity)
        self.assertIn('OcrFrameProvenance.check(ticket, FrameStore.latest', activity)
        self.assertIn('FrameStore.windowOrigin, FrameStore.captureEpoch,', activity)
        self.assertIn('result.text = ""', activity)

    def test_diagnostics_only_can_never_dispatch(self):
        for filename in ['OcrFrameProvenance.kt', 'OcrDiagnosticConsensus.kt']:
            text = (ROOT / filename).read_text()
            for banned in ['dispatchGesture', 'sourceVerified = true', 'LiveGamePermissionGate', 'claimDispatch']:
                self.assertNotIn(banned, text)
        activity = (ROOT / 'MainActivity.kt').read_text()
        for blocked in ['appVerified = false', 'tableVerified = false', 'exactActionLabelsConfirmed = false']:
            self.assertIn(blocked, activity)

    def test_stop_and_restart_erase_history(self):
        activity = (ROOT / 'MainActivity.kt').read_text()
        self.assertEqual(activity.count('ocrConsensus.reset()') >= 5, True)
        for phrase in ['OnDeviceHandState.reset("Захват остановлен")',
                       'OnDeviceHandState.reset("Аварийный STOP")',
                       'OnDeviceHandState.reset("Новый захват: фаза не установлена")']:
            self.assertIn(phrase, activity)

    def test_visual_evidence_revoked_on_window_transition(self):
        svc = (ROOT / 'CaptureService.kt').read_text()
        self.assertIn('FrameStore.observeWindow(captureEpoch, origin)', svc)
        self.assertIn('if (!assessment.safeForLocalDiagnostics) invalidateVisualEvidence()', (ROOT / 'FrameStore.kt').read_text())
        self.assertIn('windowStillMatches(origin, rootEvidence)', svc)

    def test_consensus_requires_two_distinct_frames(self):
        evidence = (ROOT / 'OcrDiagnosticConsensus.kt').read_text()
        self.assertIn('ticket.frameSequence > old.ticket.frameSequence', evidence)
        self.assertIn('delta in 1L..maxGapMs', evidence)
        self.assertIn('ticket.windowGeneration == old.ticket.windowGeneration', evidence)

    def test_consensus_rejects_ambiguous_or_changed_values(self):
        evidence = (ROOT / 'OcrDiagnosticConsensus.kt').read_text()
        self.assertIn('reading.rejected.isNotEmpty()', evidence)
        self.assertIn('old.numbers[it] == current.numbers[it]', evidence)
        self.assertIn('newTimer <= oldTimer', evidence)

    def test_one_frame_only_never_declared_consistent(self):
        evidence = (ROOT / 'OcrDiagnosticConsensus.kt').read_text()
        self.assertIn('WAITING_SECOND_FRAME', evidence)
        self.assertIn('CONSISTENT_DIAGNOSTIC_ONLY', evidence)
        self.assertIn('НЕ подтверждена', evidence)

    def test_version_and_suite_registered(self):
        gradle = (BASE / 'android/app/build.gradle.kts').read_text()
        runner = (BASE / 'tools/run_jvm_tests.sh').read_text()
        self.assertGreaterEqual(int(re.search(r"versionCode\s*=\s*(\d+)", gradle).group(1)), 60)
        self.assertRegex(gradle, r'versionName = "2\.14\.\d+[^"]*"')
        self.assertIn('OcrProvenanceScenarios', runner)
        self.assertIn('OcrDiagnosticScenarios', runner)

if __name__ == '__main__':
    unittest.main()
