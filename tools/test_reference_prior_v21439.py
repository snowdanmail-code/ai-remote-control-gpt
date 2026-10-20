"""Preserve historical v2.14.39 evidence, but gate against the installed v2.14.41 APK."""
import json
import unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
class BaselineV39(unittest.TestCase):
    def test_archive_is_actual_v39(self):
        data=json.loads((ROOT/'tools/reference_prior_apk_v2.14.39.json').read_text())
        self.assertEqual('com.example.airemote.dev',data['package'])
        self.assertEqual(73,data['version_code'])
        self.assertEqual('622e0901466499a7faa32235b22d230f6f7a3dfe13d2a0d2e5d7da501b4ae2ba',data['source_apk_sha256'])
        self.assertEqual(64,len(data['signer_sha256']))
    def test_ci_gate_uses_immediately_previous_apk(self):
        workflow=(ROOT/'.github/workflows/android.yml').read_text()
        self.assertIn('reference_prior_apk_v2.14.41.json',workflow)
        self.assertLess(workflow.index('verify_android_upgrade.py'),workflow.index('actions/upload-artifact@v4'))
if __name__=='__main__': unittest.main()
