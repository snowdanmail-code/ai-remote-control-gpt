"""T042 fail-closed signing/Android instrumentation release gate regression tests."""
import json
import tempfile
import unittest
from pathlib import Path
from prepare_t042_release import stage

ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT/'.github/workflows/android.yml'
BASELINE = ROOT/'tools/reference_prior_apk_v2.14.41.json'

class T042ProtectedReleaseGateTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.w=WORKFLOW.read_text(encoding='utf-8')
    def test_version75_baseline_is_actual_21441(self):
        b=json.loads(BASELINE.read_text(encoding='utf-8'))
        self.assertEqual('com.example.airemote.dev', b['package'])
        self.assertEqual(75, b['version_code'])
        self.assertEqual('511567f44b5e527b8b388d0ea2a66ad30725a856ef76670c027b88c25007cd2c', b['signer_sha256'])
        self.assertEqual(64,len(b['source_apk_sha256']))
    def test_signer_and_instrumentation_precede_release(self):
        w=self.w
        self.assertLess(w.index('prior signing certificate mismatch'),w.index(':app:connectedDebugAndroidTest'))
        self.assertLess(w.index(':app:connectedDebugAndroidTest'),w.index('tools/prepare_t042_release.py'))
        self.assertLess(w.index('tools/prepare_t042_release.py'),w.index('verify_android_upgrade.py'))
        self.assertLess(w.index('verify_android_upgrade.py'),w.index('actions/upload-artifact@v4'))
        self.assertIn('api-level: 36',w)
        self.assertIn('test -c /dev/kvm',w)
        self.assertIn('reference_prior_apk_v2.14.41.json',w)
    def test_no_plaintext_key_or_password_in_repository(self):
        self.assertNotIn('storePassword = "android"',(ROOT/'android/app/build.gradle.kts').read_text())
        self.assertNotIn('password: android',self.w)
        self.assertNotIn('debug.keystore',self.w)
        self.assertNotIn('EXISTING_APK_STORE_PASSWORD=$EXISTING_',self.w)
        self.assertIn('secrets.EXISTING_APK_KEYSTORE_B64',self.w)
    def test_candidate_staging_checks_checkpoint_and_refuses_double_release(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'build.gradle.kts';m=Path(d)/'marker.txt'
            p.write_text('x\nversionCode = 75\nversionName = "2.14.41-in-app-oauth-hardening"\n')
            m.write_text('UNRELEASED T042 CHECKPOINT')
            stage(p,m)
            self.assertIn('versionCode = 76',p.read_text())
            self.assertIn('versionName = "2.14.42-t042-controlled"',p.read_text())
            with self.assertRaises(ValueError):stage(p,m)
    def test_staging_fails_for_missing_checkpoint(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'build.gradle.kts';m=Path(d)/'missing'
            p.write_text('versionCode = 75\nversionName = "2.14.41-in-app-oauth-hardening"\n')
            with self.assertRaises(ValueError):stage(p,m)
    def test_manual_or_exactly_one_gated_push_not_uncontrolled_release(self):
        self.assertIn('workflow_dispatch:', self.w)
        self.assertIn('paths:', self.w)
        self.assertIn('      - .github/workflows/android.yml', self.w)
        self.assertRegex(self.w, r"github\.event\.head_commit\.message == 'T042 signed code[0-9]+ [^']+ pilot'")
        self.assertEqual(1, self.w.count("github.event.head_commit.message =="))
        self.assertIn("inputs.promote_t042 == true || inputs.samsung_prototype == true", self.w)
        self.assertIn("!inputs.samsung_prototype && github.event_name != 'push'", self.w)
        self.assertRegex(self.w, r'Stage explicitly experimental Samsung Code[0-9]+[^\n]*\(runtime NOT_RUN\)')
        self.assertIn('environment: android-signing', self.w)
        self.assertIn('if: always()', self.w)
        self.assertIn("NOT_RUN_IN_THIS_PROTOTYPE_BUILD", self.w)
if __name__=='__main__':unittest.main()
