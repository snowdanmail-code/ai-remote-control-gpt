import unittest
from pathlib import Path

R = Path(__file__).resolve().parents[1]
S = R/'android/app/src/main/java/com/example/airemote'

class ChatGptCloudIntegrationSource(unittest.TestCase):
    def setUp(self):
        self.cloud = (S/'ChatGptCloudBridge.kt').read_text()
        self.auth = (S/'ChatGptAuthProtocol.kt').read_text()
        self.manifest = (R/'android/app/src/main/AndroidManifest.xml').read_text()
        self.ui = (S/'MainActivity.kt').read_text()

    def test_protocol_uses_official_endpoints_and_pkce(self):
        for term in ('auth.openai.com', 'code_challenge_method', 'S256', 'dynamic_agent_client',
                     'ext_agent_host_id', 'chatgpt.tokens.use.direct', '/auth/callback'):
            self.assertIn(term, self.auth)
        self.assertNotIn('chatgpt.com/backend-api', self.auth + self.cloud)

    def test_no_hardcoded_api_key_or_password_field(self):
        self.assertNotIn('sk-proj-', self.cloud + self.auth)
        self.assertNotIn('sk-svcacct-', self.cloud + self.auth)
        self.assertNotIn('PasswordTransformationMethod', self.ui)
        self.assertIn('Intent.ACTION_VIEW', self.cloud)
        self.assertIn('CATEGORY_BROWSABLE', self.cloud)

    def test_callback_binds_to_loopback_and_state(self):
        self.assertIn('InetAddress.getByName("127.0.0.1")', self.cloud)
        self.assertIn('validateCallback(path, redirect, state, issued)', self.cloud)
        self.assertIn('secureEquals(expectedState', self.auth)
        self.assertIn('server.soTimeout = 600_000', self.cloud)

    def test_validate_signed_identity(self):
        for term in ('Signature.getInstance("SHA256withRSA")', 'sig.verify(decode(parts[2]))',
                     'ChatGptAuthProtocol.JWKS', 'ChatGptIdentityPolicy.validate(',
                     'issuer = claims.optString("iss")', 'getString("sub")'):
            self.assertIn(term, self.cloud + (S / "ChatGptIdTokenVerifier.kt").read_text())

    def test_tokens_encrypted_and_not_in_url(self):
        self.assertIn('AndroidKeyStore', self.cloud)
        self.assertIn('AES/GCM/NoPadding', self.cloud)
        self.assertIn('refresh_token', self.cloud)
        self.assertIn('keySize(256)', self.cloud.replace('setKeySize','keySize'))
        self.assertNotIn('access_token=', self.cloud)

    def test_cloud_uses_preview_supported_responses(self):
        for term in ('/v1/models', '/v1/responses', '"store", false', '"stream", true',
                     'response.completed', 'response.output_text.delta'):
            self.assertIn(term, self.cloud)
        for unsupported in ('"temperature"', '"previous_response_id"', '"background"'):
            self.assertNotIn(unsupported, self.cloud)

    def test_cloud_does_not_tap_or_publish_ocr(self):
        for method in ('requestVerifiedAction(', 'dispatchGesture(', 'dispatchVerifiedTap(',
                       'FrameStore.latest', 'OnDeviceVerifiedDispatchAdmission'):
            self.assertNotIn(method, self.cloud)
        self.assertNotIn('api.openai.com', (S/'CaptureService.kt').read_text())
        self.assertNotIn('api.openai.com', (S/'VerifiedLocalPokerStrategy.kt').read_text())

    def test_ui_has_login_test_logout(self):
        for term in ('ВОЙТИ ЧЕРЕЗ CHATGPT', 'ПРОВЕРИТЬ ПОДКЛЮЧЕНИЕ ОБЛАЧНОГО ИИ',
                     'ВЫЙТИ ИЗ CHATGPT', 'ChatGptCloudBridge.startSignIn',
                     'ChatGptCloudBridge.testInference'):
            self.assertIn(term, self.ui)

    def test_android_permissions_and_no_backup(self):
        self.assertIn('android.permission.INTERNET', self.manifest)
        self.assertIn('android:allowBackup="false"', self.manifest)
        self.assertIn('android:usesCleartextTraffic="false"', self.manifest)

if __name__ == '__main__': unittest.main()
