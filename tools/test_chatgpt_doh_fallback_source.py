import unittest
from pathlib import Path
R = Path(__file__).resolve().parents[1]
SRC = R / "android/app/src/main/java/com/example/airemote"

class SecureDohFallbackContracts(unittest.TestCase):
    def setUp(self):
        self.bridge = (SRC/"ChatGptCloudBridge.kt").read_text()
        self.doh = (SRC/"ChatGptDohTransport.kt").read_text()

    def test_dns_only_for_official_openai_endpoints(self):
        self.assertIn('setOf("auth.openai.com", "api.openai.com")', self.doh)
        self.assertIn('url.protocol == "https"', self.doh)
        self.assertIn('bootstrapDnsHosts', self.doh)

    def test_no_transport_security_bypass(self):
        self.assertIn('retryOnConnectionFailure(false)', self.doh)
        self.assertIn('followRedirects(false)', self.doh)
        self.assertIn('followSslRedirects(false)', self.doh)
        self.assertNotIn('HostnameVerifier', self.doh)
        self.assertNotIn('TrustManager', self.doh)

    def test_token_fallback_only_before_grant_is_written(self):
        self.assertIn('ChatGptTokenConnect.openBeforeBody', self.bridge)
        self.assertIn('return dohJson(url, "POST"', self.bridge)
        self.assertIn('catch (ex: java.net.UnknownHostException)', self.bridge)

    def test_model_dns_path_chosen_by_get_before_post(self):
        self.assertIn('lastModelsUsedDoh = false', self.bridge)
        self.assertIn('if (lastModelsUsedDoh)', self.bridge)
        self.assertIn('if (url == "https://api.openai.com/v1/models") lastModelsUsedDoh = true', self.bridge)
        self.assertIn('SECURE_DNS_FALLBACK_UNAVAILABLE', self.bridge)

if __name__ == "__main__":
    unittest.main()
