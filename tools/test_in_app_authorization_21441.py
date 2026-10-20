"""Contracts for in-app browser-owned authorization, no credential harvesting."""
import unittest
from pathlib import Path
R=Path(__file__).resolve().parents[1]
B=(R/'android/app/src/main/java/com/example/airemote/ChatGptCloudBridge.kt').read_text()
M=(R/'android/app/src/main/AndroidManifest.xml').read_text()
G=(R/'android/app/build.gradle.kts').read_text()
UI=(R/'android/app/src/main/java/com/example/airemote/MainActivity.kt').read_text()

class InAppAuthContracts(unittest.TestCase):
 def test_browser_owned_in_app_tab(self):
  self.assertIn('CustomTabsIntent.Builder()',B)
  self.assertIn('launchUrl(activity, Uri.parse(url))',B)
  self.assertIn('ActivityNotFoundException',B)
  self.assertIn('androidx.browser:browser:1.8.0',G)
  self.assertNotIn('WebView(',B)
  self.assertNotIn('PasswordTransformationMethod',B + UI)
 def test_mfa_wait_and_security(self):
  self.assertIn('server.soTimeout = 600_000',B)
  self.assertIn('validateCallback(path, redirect, state, issued)',B)
  self.assertIn('verifyIdToken(idToken, returned.issuedClientId, nonce)',B)
  self.assertIn('AndroidKeyStore',B)
  self.assertIn('code_verifier',B)
 def test_network_bound_fallback_only_before_grant_transmission(self):
  self.assertIn('active?.openConnection(uri)',B)
  self.assertIn('ChatGptTokenConnect.openBeforeBody',B)
  self.assertLess(B.index('ChatGptTokenConnect.openBeforeBody'),B.index('conn.outputStream'))
  self.assertIn('android.permission.ACCESS_NETWORK_STATE',M)
  self.assertIn('UnknownHostException', (R/'android/app/src/main/java/com/example/airemote/ChatGptTokenConnect.kt').read_text())
 def test_inference_proves_account_connection(self):
  self.assertIn("verifyAfterSignIn = usable",B)
  self.assertIn("operations.finishSignIn()",B)
  self.assertIn("if (verifyAfterSignIn && generation == operations.generation())",B)
  self.assertIn("testInference(activity.applicationContext)",B)
  self.assertIn("response.completed",B)
 def test_login_error_not_hidden_by_no_session(self):
  self.assertIn("lastLoginDiagnostic.contains(\"AUTH_\")",B)
  self.assertIn("Последняя ошибка входа:",B)
  self.assertIn("val usefulMessage",B)
 def test_incremental_update_and_stop(self):
  self.assertIn('versionCode = 75',G)
  self.assertIn('applicationIdSuffix = ".dev"',G)
  self.assertIn('СТОП',UI)
  self.assertIn('вкладку OpenAI',UI)
if __name__=='__main__': unittest.main()
