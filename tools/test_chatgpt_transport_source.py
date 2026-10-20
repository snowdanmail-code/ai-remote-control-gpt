import unittest
from pathlib import Path
P = Path(__file__).resolve().parents[1]
SRC = P / 'android/app/src/main/java/com/example/airemote'

class TransportSourceContracts(unittest.TestCase):
    def test_diagnostic_button_without_oauth(self):
        ui = (SRC/'MainActivity.kt').read_text()
        self.assertIn('ПРОВЕРИТЬ СЕТЬ OPENAI (БЕЗ ВХОДА)', ui)
        self.assertIn('ChatGptCloudBridge.diagnoseOpenAiNetwork', ui)

    def test_no_credentials_in_probe(self):
        bridge = (SRC/'ChatGptCloudBridge.kt').read_text()
        probe = bridge.split('private fun probeEndpoint(')[1].split('    internal fun request(')[0]
        self.assertNotIn('InetAddress.getAllByName(host)', probe)
        self.assertIn('conn.responseCode',probe)
        self.assertIn('"GET"', probe)
        self.assertNotIn('Bearer', probe)
        self.assertNotIn('access_token', probe)
        self.assertNotIn('authorization_code', probe)

    def test_managed_reauth_registration(self):
        bridge = (SRC/'ChatGptCloudBridge.kt').read_text()
        self.assertIn('rememberRegisteredClientId(activity, returned.issuedClientId)', bridge)
        self.assertIn('?: registeredClientId(activity)', bridge)
        self.assertIn('ChatGptAuthProtocol.validateCallback(path, redirect, state, issued)', bridge)

    def test_keep_android_internet_and_tls_verification(self):
        manifest = (P/'android/app/src/main/AndroidManifest.xml').read_text()
        self.assertIn('android.permission.INTERNET', manifest)
        self.assertIn('android:usesCleartextTraffic="false"', manifest)
        bridge = (SRC/'ChatGptCloudBridge.kt').read_text()
        self.assertIn('uri.protocol == "https"', bridge)
        self.assertNotIn('HostnameVerifier', bridge)
        self.assertNotIn('TrustManager', bridge)

if __name__ == '__main__': unittest.main()
