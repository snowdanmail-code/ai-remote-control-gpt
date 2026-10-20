import hashlib
import json
import unittest
from pathlib import Path
R=Path(__file__).resolve().parents[1]

class BaselineMetadataContract(unittest.TestCase):
    def test_recovered_prior_apk_metadata(self):
        record=json.loads((R/'tools/reference_prior_apk_v2.14.36.json').read_text())
        self.assertEqual('com.example.airemote.dev', record['package'])
        self.assertEqual(70, record['version_code'])
        self.assertEqual(64,len(record['signer_sha256']))
        self.assertEqual(64,len(record['source_apk_sha256']))
        self.assertEqual('APK Signature Scheme v2', record['signing_scheme'])
    def test_prior_cert_must_not_be_treated_as_signing_key(self):
        record=json.loads((R/'tools/reference_prior_apk_v2.14.36.json').read_text())
        self.assertNotIn('private_key', record)
        self.assertNotIn('keystore', record)
        self.assertNotIn('password', record)

if __name__=='__main__': unittest.main()
