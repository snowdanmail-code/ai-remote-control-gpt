"""Build gate tests do not require Android SDK or a real APK."""
import importlib.util
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('gate', ROOT/'verify_android_upgrade.py')
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)

CERT = '12ab'*16


class AndroidUpgradeGateTest(unittest.TestCase):
    def test_instrumentation_may_omit_update_version(self):
        dump="package: name='com.example.airemote.dev.test' versionCode='' versionName=''"
        self.assertEqual('com.example.airemote.dev.test',gate.parse_package(dump,require_version=False)['package'])
        with self.assertRaises(ValueError):gate.parse_package(dump)

    def test_instrumentation_still_requires_aapt_package_identity(self):
        with self.assertRaises(ValueError):gate.parse_package('no package metadata',require_version=False)

    def setUp(self):
        self.baseline = {'package': 'com.example.airemote.dev', 'signer_sha256': CERT, 'version_code': 70}
        self.candidate = {'package': 'com.example.airemote.dev', 'signer_sha256': CERT,
                          'version_code': 75, 'version_name': '2.14.41-in-app-oauth-hardening-test',
                          'apk_sha256': 'ef'*32, 'size_bytes': 10000}

    def test_valid_upgrade_metadata(self):
        self.assertEqual('VERIFIED_FOR_IN_PLACE_UPDATE', gate.verify(self.candidate, self.baseline)['status'])

    def test_package_change_fails(self):
        self.candidate['package'] = 'com.example.airemote'
        with self.assertRaisesRegex(ValueError, 'PACKAGE_MISMATCH'):
            gate.verify(self.candidate, self.baseline)

    def test_certificate_change_fails(self):
        self.candidate['signer_sha256'] = 'be'*32
        with self.assertRaisesRegex(ValueError, 'SIGNATURE_MISMATCH'):
            gate.verify(self.candidate, self.baseline)

    def test_version_same_or_lower_fails(self):
        for version in (70, 69):
            with self.subTest(version=version):
                self.candidate['version_code'] = version
                with self.assertRaisesRegex(ValueError, 'VERSION_NOT_INCREASED'):
                    gate.verify(self.candidate, self.baseline)

    def test_invalid_certificate_format_fails(self):
        self.candidate['signer_sha256'] = 'not a certificate'
        with self.assertRaises(ValueError):
            gate.verify(self.candidate, self.baseline)

    def test_missing_name_fails(self):
        self.candidate['version_name'] = ''
        with self.assertRaisesRegex(ValueError, 'VERSION_NAME_MISSING'):
            gate.verify(self.candidate, self.baseline)

    def test_colon_separated_cert_accepted(self):
        self.baseline['signer_sha256'] = ':'.join(CERT[i:i+2] for i in range(0, 64, 2))
        self.assertEqual('VERIFIED_FOR_IN_PLACE_UPDATE', gate.verify(self.candidate, self.baseline)['status'])

    def test_invalid_or_missing_baseline_rejected_by_cli(self):
        self.assertRaises(ValueError, gate.normalize_fingerprint, "not available")


if __name__ == '__main__':
    unittest.main()
