import unittest
from pathlib import Path
R = Path(__file__).resolve().parents[1]

class LoginPreflightWiring(unittest.TestCase):
    def test_preflight_occurs_before_browser(self):
        s = (R/'android/app/src/main/java/com/example/airemote/ChatGptCloudBridge.kt').read_text()
        begin = s.split('fun startSignIn(')[1].split('/** Safe connectivity probe')[0]
        self.assertLess(begin.index('ChatGptLoginPreflight.assess'), begin.index('activity.startActivity'))
        self.assertIn('!readiness.canOpenBrowser', begin)
        self.assertIn('ChatGptCloudStep.PREFLIGHT', begin)

    def test_status_code_hides_credentials(self):
        s = (R/'android/app/src/main/java/com/example/airemote/ChatGptCloudDiagnostics.kt').read_text()
        self.assertIn('PREFLIGHT("AUTH_PREFLIGHT"', s)
        self.assertNotIn('token.toString()', s)

    def test_jvm_scenarios_wired(self):
        s = (R/'tools/run_jvm_tests.sh').read_text()
        self.assertIn('ChatGptLoginPreflightScenarios', s)

if __name__ == '__main__': unittest.main()
