import re
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
CODE = ROOT / 'android/app/src/main/java/com/example/airemote'

class AtomicFramePublicationSourceTest(unittest.TestCase):
    def setUp(self):
        self.store = (CODE / 'FrameStore.kt').read_text()
        self.service = (CODE / 'CaptureService.kt').read_text()

    def test_origin_and_stop_use_same_lock(self):
        self.assertIn('@Synchronized fun observeWindow(', self.store)
        self.assertIn('@Synchronized fun clear()', self.store)
        self.assertIn('@Synchronized fun commitIfCurrent(', self.store)

    def test_origin_checks_epoch_and_activity(self):
        self.assertIn('!isCapturing || captureEpoch != expectedEpoch', self.store)
        self.assertIn('assessment.captureEpoch != expectedEpoch', self.store)
        self.assertIn('windowOrigin !== expectedOrigin', self.store)

    def test_reject_invalid_origin_and_erase_data(self):
        self.assertIn('!assessment.safeForLocalDiagnostics) invalidateVisualEvidence()', self.store)
        self.assertIn('!expectedOrigin.safeForLocalDiagnostics', self.store)

    def test_service_uses_atomic_publication_for_cards_and_jpeg(self):
        self.assertEqual(self.service.count('FrameStore.commitIfCurrent('), 3)  # player, spectator, JPEG: all atomic
        self.assertIn('FrameStore.observeWindow(captureEpoch, origin)', self.service)
        self.assertIn('FrameStore.latest = FrameInfo(', self.service)
        self.assertIn('FrameStore.lastCards = signal', self.service)

    def test_predicate_rechecks_stop_visibility_and_root(self):
        self.assertEqual(self.service.count('windowStillMatches(origin, rootEvidence)'), 5)  # spectator board and independent spectator OCR both check root
        self.assertGreaterEqual(self.service.count('AppVisibilityGate.canPublish(visibilityTicket)'), 3)
        self.assertIn('!OperatorStopStore.isStopped(applicationContext)', self.service)

    def test_notification_stop_clears_immediately(self):
        begin = self.service.index('ACTION_STOP -> {')
        end = self.service.index('ACTION_START -> {', begin)
        stop_branch = self.service[begin:end]
        self.assertIn('OperatorStopStore.stop(this)', stop_branch)
        self.assertIn('FrameStore.clear()', stop_branch)
        self.assertLess(stop_branch.index('FrameStore.clear()'), stop_branch.index('stopSelf()'))

    def test_rearm_only_from_operator_not_capture(self):
        self.assertNotIn('OnDeviceLiveActionControl.guard.armByOperator()', self.service)
        self.assertIn('OperatorStopStore.armFromOperator', (CODE/'MainActivity.kt').read_text())

    def test_capture_mark_active_is_epoch_scoped(self):
        self.assertIn('@Synchronized fun beginActive(expectedEpoch: Long)', self.store)
        self.assertIn('FrameStore.beginActive(requestedEpoch)', self.service)
        self.assertIn('val requestedEpoch = FrameStore.captureEpoch', self.service)

    def test_no_table_or_game_rights_in_atomic_store(self):
        for disallowed in ('dispatchGesture(', 'sourceVerified = true', 'grantForVerifiedEnvironment', 'platformAutomationAuthorized = true'):
            self.assertNotIn(disallowed, self.store)
        activity = (CODE/'MainActivity.kt').read_text()
        self.assertIn('tableVerified = false', activity)
        self.assertIn('exactActionLabelsConfirmed = false', activity)

    def test_version_runner_and_manual_ci(self):
        gradle = (ROOT/'android/app/build.gradle.kts').read_text()
        runner = (ROOT/'tools/run_jvm_tests.sh').read_text()
        workflow = (ROOT/'.github/workflows/android.yml').read_text()
        self.assertGreaterEqual(int(re.search(r"versionCode\s*=\s*(\d+)", gradle).group(1)), 60)
        self.assertIn('FramePublicationScenarios', runner)
        self.assertIn('workflow_dispatch:', workflow)
        self.assertRegex(workflow, r'AI-Remote-Control-GPT-T042-[A-Za-z0-9-]+-Code[0-9]+\.apk')

if __name__ == '__main__': unittest.main()
