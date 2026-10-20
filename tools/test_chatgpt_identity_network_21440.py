"""Regression against misclassifying JWKS network errors as invalid identity."""
import re
import unittest
from pathlib import Path
R=Path(__file__).resolve().parents[1]
S=R/'android/app/src/main/java/com/example/airemote'

class IdentityNetworkRegression(unittest.TestCase):
    def test_jwks_network_exceptions_not_mapped_to_bad_identity(self):
        bridge=(S/'ChatGptCloudBridge.kt').read_text()
        self.assertIn('val claims = verifyIdToken(idToken, returned.issuedClientId, nonce)', bridge)
        self.assertNotRegex(bridge, r'verifyIdToken\(idToken, returned\.issuedClientId, nonce\)\s*}\s*catch\s*\(_:\s*Exception\)')
        self.assertIn('getJson(ChatGptAuthProtocol.JWKS)',bridge)
        self.assertIn('ChatGptTokenConnect.openBeforeBody',bridge)
        self.assertNotIn('ChatGptDnsAdmission.preflight',bridge)
        self.assertLess(bridge.index('ChatGptTokenConnect.openBeforeBody'), bridge.index('conn.outputStream'))
    def test_valid_id_token_header_does_not_require_typ(self):
        bridge=(S/'ChatGptCloudBridge.kt').read_text() + (S/'ChatGptIdTokenVerifier.kt').read_text()
        self.assertIn('header.optString("alg") != "RS256"',bridge)
        self.assertNotIn('header.optString("typ"',bridge)
        self.assertIn('Signature.getInstance("SHA256withRSA")',bridge)
        self.assertIn('sig.verify(decode(parts[2]))',bridge)
    def test_claim_validation_remains_strict(self):
        policy=(S/'ChatGptIdentityPolicy.kt').read_text()
        for s in ('ChatGptAuthProtocol.ISSUER','expectedClient !in claims.audiences','expirationSeconds <= nowSeconds','ChatGptAuthProtocol.secureEquals(expectedNonce, claims.nonce)','claims.subject.isBlank()'):
            self.assertIn(s,policy)
    def test_no_oauth_replay_on_dns_failure(self):
        connector=(S/'ChatGptTokenConnect.kt').read_text()
        self.assertIn('UnknownHostException',connector)
        self.assertNotIn('code_verifier',connector)
        self.assertNotIn('authorization_code',connector)
        self.assertNotIn('postForm(',connector)
    def test_no_additional_buttons(self):
        main=(S/'MainActivity.kt').read_text()
        self.assertNotIn('ПРОВЕРИТЬ JWKS ОТДЕЛЬНО',main)
        self.assertIn('primaryActions',main)
    def test_workflow_yaml_no_blank_env_step(self):
        workflow=(R/'.github/workflows/android.yml').read_text()
        self.assertNotIn('        env:\n        run:',workflow)
    def test_compatibility_version_increment(self):
        build=(R/'android/app/build.gradle.kts').read_text()
        self.assertIn('versionCode = 75',build)
        self.assertIn('applicationIdSuffix = ".dev"',build)

if __name__=='__main__': unittest.main()
