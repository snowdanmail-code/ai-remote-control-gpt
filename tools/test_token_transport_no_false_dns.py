"""No redundant DNS gate before OAuth token POST. Regression for v2.14.39 AUTH_TOKEN_DNS_FAILURE."""
from pathlib import Path
import unittest
R=Path(__file__).resolve().parents[1]
BRIDGE=(R/'android/app/src/main/java/com/example/airemote/ChatGptCloudBridge.kt')
HELPER=(R/'android/app/src/main/java/com/example/airemote/ChatGptTokenConnect.kt')

class TokenTransportRegression(unittest.TestCase):
    def test_no_separate_dns_gate_on_post(self):
        body=BRIDGE.read_text().split('internal fun request(')[1].split('private fun getJson(')[0]
        self.assertNotIn('InetAddress.getAllByName', body)
        self.assertNotIn('ChatGptDnsAdmission',body)
        self.assertIn('ChatGptTokenConnect.openBeforeBody',body)
        self.assertIn('it.connect(); emit(null)',body)
        self.assertIn('setFixedLengthStreamingMode(body.size)',body)
    def test_one_write_no_post_replay(self):
        body=BRIDGE.read_text().split('internal fun request(')[1].split('private fun getJson(')[0]
        self.assertEqual(1,body.count('conn.outputStream.use { trace.phase = ChatGptTransportTrace.Phase.BODY_WRITE; it.write(body) }'))
        self.assertNotIn('postForm(',body)
        self.assertLess(body.index('ChatGptTokenConnect.openBeforeBody'),body.index('conn.outputStream.use'))
    def test_jwks_probe_checks_actual_https_not_independent_dns(self):
        body=BRIDGE.read_text().split('private fun probeEndpoint(')[1].split('internal fun request(')[0]
        self.assertNotIn('InetAddress.getAllByName',body)
        self.assertIn('conn.responseCode',body)
        self.assertIn('HTTP_$code',body)
    def test_no_insecure_fallback(self):
        code=BRIDGE.read_text()
        self.assertIn('uri.protocol == "https"',code)
        self.assertIn('conn.outputStream.use',code)
        self.assertNotIn('http://auth.openai.com',code)
        helper=HELPER.read_text()
        self.assertIn('catch (ex: UnknownHostException)',helper)
        self.assertIn('catch (ex: Exception)',helper)
        self.assertIn('if (attempt == 1) throw ex',helper)
        self.assertNotIn('code_verifier',helper)
    def test_version_and_minimal_ui(self):
        build=(R/'android/app/build.gradle.kts').read_text()
        self.assertIn('versionCode = 75',build)
        self.assertIn('applicationIdSuffix = ".dev"',build)
        self.assertIn('primaryActions',(R/'android/app/src/main/java/com/example/airemote/MainActivity.kt').read_text())

if __name__=='__main__': unittest.main()

