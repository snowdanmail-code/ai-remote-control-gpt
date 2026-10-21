import re
from pathlib import Path
import unittest

BASE = Path(__file__).resolve().parents[1]
APP = BASE / 'android/app/src/main/java/com/example/airemote'

class TableHandBindingGuardSourceTest(unittest.TestCase):
    def test_guard_is_separate_non_android_module(self):
        text = (APP/'TableHandIdentityGuard.kt').read_text()
        self.assertIn('IndependentTableHandAssertion',text)
        self.assertIn('independentSourceVerified',text)
        self.assertIn('TableHandBindingStatus',text)
        self.assertNotIn('import android.',text)

    def test_fail_closed_for_absent_independent_proof(self):
        text = (APP/'TableHandIdentityGuard.kt').read_text()
        self.assertIn('assertion == null || !assertion.independentSourceVerified',text)
        self.assertIn('UNVERIFIED_IDENTITY',text)
        self.assertIn('ticket.frameSequence <= old.ticket.frameSequence', text)
        self.assertIn('old.proof.handId != assertion.handId',text)
        self.assertIn('old.proof.tableId != assertion.tableId',text)

    def test_activity_has_no_fake_identity_provider(self):
        text = (APP/'MainActivity.kt').read_text()
        self.assertIn('identityGuard.inspect(null, ticket, agreement,',text)
        self.assertIn('Привязка к столу/раздаче:',text)
        self.assertIn('appVerified = false',text)
        self.assertIn('tableVerified = false',text)
        self.assertIn('strategyConnected = OnDeviceAutonomousRuntime.strategyInstalled()',text)

    def test_stop_reset_and_ocr_failure_forget_binding(self):
        text=(APP/'MainActivity.kt').read_text()
        self.assertGreaterEqual(text.count('identityGuard.reset()'),5)
        self.assertIn('OperatorStopStore.stop(this)',text)
        self.assertIn('result.text = ""',text)

    def test_version_and_runner(self):
        s=(BASE/'android/app/build.gradle.kts').read_text()
        self.assertGreaterEqual(int(re.search(r"versionCode\s*=\s*(\d+)", s).group(1)), 60)
        self.assertRegex(s, r'versionName = "2\.14\.\d+[^"]*"')
        r=(BASE/'tools/run_jvm_tests.sh').read_text()
        self.assertIn('TableHandIdentityScenarios',r)

    def test_workflow_is_manual_and_versioned(self):
        s=(BASE/'.github/workflows/android.yml').read_text()
        self.assertIn('workflow_dispatch:',s)
        self.assertIn('AI-Remote-Control-GPT-T042-Capture-Samsung-Code78.apk',s)
        self.assertIn('ai-remote-control-t042-capture-samsung-code78',s)
        self.assertNotIn('v2.14.13-WINDOW-ORIGIN',s)

    def test_no_external_dispatched_action_in_identity_module(self):
        text=(APP/'TableHandIdentityGuard.kt').read_text()
        for banned in ['dispatchGesture(', 'LiveGamePermissionGate', 'sendGesture(', 'sourceVerified = true']:
            self.assertNotIn(banned,text)

    def test_jvm_provenance_tests_present(self):
        tests=(BASE/'tools/TableHandIdentityScenarios.kt').read_text()
        for token in ['"Hand change immediately revokes"', '"Table switch revokes"',
                      '"STOP always revokes"', '"Window mismatch"', '"Long inter-frame gap"',
                      '"No independent proof"']:
            self.assertIn(token,tests)

if __name__=='__main__':
    unittest.main()
