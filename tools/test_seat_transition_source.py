import re
"""Source-level guard: read-only diagnostics must never reach gameplay/persistence."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / 'android/app/src/main/java/com/example/airemote'

class SeatTransitionSourceTests(unittest.TestCase):
    def test_module_is_read_only(self):
        code = (APP/'SeatActionTransitionDiagnostics.kt').read_text()
        for forbidden in ('dispatchGesture(', 'ConfirmedOpponentAction(',
                          'LiveOpponentAwareness.observe(', 'commitHandAtomically(',
                          'OnDeviceLiveActionControl.', 'grantForVerifiedEnvironment('):
            self.assertNotIn(forbidden, code)
        self.assertIn('canEnterOpponentHistory: Boolean get() = false', code)
        self.assertIn('canAuthorizeGameplay: Boolean get() = false', code)

    def test_multiple_observations_and_full_context_required(self):
        code = (APP/'SeatActionTransitionDiagnostics.kt').read_text()
        for field in ('independentContextVerified', 'independentStateVerified',
                      'after.seatContributions != settled.seatContributions',
                      'before.turnSeat != seat', 'operatorStopped',
                      'sameContext(before, after)', 'later(after, settled)',
                      'nowMs - settled.ticket.capturedAtMs > MAX_AGE_MS',
                      'cueReport.candidates.size != 1'):
            self.assertIn(field, code)

    def test_no_default_1win_layout_or_fabricated_source(self):
        code = (APP/'SeatActionTransitionDiagnostics.kt').read_text()
        self.assertNotIn('com.onewin', code)
        self.assertNotIn('sourceVerified = true', code)
        self.assertNotIn('Screenshot', code)

    def test_tests_registered_and_release_incremented(self):
        runner = (ROOT/'tools/run_jvm_tests.sh').read_text()
        self.assertIn('com.example.airemote.SeatTransitionScenarios', runner)
        self.assertTrue((ROOT/'tools/SeatTransitionScenarios.kt').exists())
        self.assertTrue((APP.parents[4]/'test/java/com/example/airemote/SeatActionTransitionJvmTest.kt').exists())
        self.assertGreaterEqual(int(re.search(r"versionCode\s*=\s*(\d+)", (ROOT/'android/app/build.gradle.kts').read_text()).group(1)), 60)
        self.assertIn('AI-Remote-Control-GPT-T042-Capture-Samsung-Code78.apk', (ROOT/'.github/workflows/android.yml').read_text())

if __name__ == '__main__': unittest.main()
