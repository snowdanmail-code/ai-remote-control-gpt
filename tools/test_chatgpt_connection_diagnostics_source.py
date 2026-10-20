import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CLOUD = ROOT/'android/app/src/main/java/com/example/airemote/ChatGptCloudBridge.kt'
UI = ROOT/'android/app/src/main/java/com/example/airemote/MainActivity.kt'
DIAG = ROOT/'android/app/src/main/java/com/example/airemote/ChatGptCloudDiagnostics.kt'

class CloudConnectionDiagnosticWiring(unittest.TestCase):
    def test_callback_page_no_false_success(self):
        code = CLOUD.read_text()
        self.assertIn('Ответ OpenAI получен.', code)
        self.assertIn('Это ещё не подтверждение входа.', code)
        self.assertNotIn('<h3>Вход передан приложению.', code)

    def test_in_progress_prevents_premature_cloud_request(self):
        code = CLOUD.read_text()
        self.assertIn('if (!operations.tryBeginRequest())', code)
        self.assertIn('AUTH_IN_PROGRESS', code)

    def test_specific_stages_and_http(self):
        code = CLOUD.read_text()
        for val in ('ChatGptCloudStep.TOKEN', 'ChatGptCloudStep.IDENTITY', 'ChatGptCloudStep.MODELS',
                    'ChatGptCloudStep.RESPONSES', 'throw httpError(c)', 'classifiedHttpError(c.responseCode',
                    'ChatGptCloudProblem.NO_SESSION', 'ChatGptCloudProblem.MISSING_PERMISSION'):
            self.assertIn(val, code)
        self.assertNotIn('${safeError(ex)}', code)

    def test_status_diagnostics_visible(self):
        self.assertIn('ChatGptCloudBridge.statusDetail', UI.read_text())
        self.assertIn('ChatGPT: вход выполняется', CLOUD.read_text())

    def test_no_credentials_in_diagnostics(self):
        code = DIAG.read_text()
        self.assertIn('CLOUD_MODELS', code)
        self.assertIn('ChatGptCloudProblem.NO_MODELS_ARRAY', code)
        self.assertNotIn('Bearer $', code)
        self.assertNotIn('getString("access_token")', code)

    def test_does_not_modify_gesture_code(self):
        code = CLOUD.read_text()
        self.assertNotIn('dispatchGesture(', code)
        self.assertNotIn('dispatchVerifiedTap(', code)

if __name__ == '__main__':
    unittest.main()
