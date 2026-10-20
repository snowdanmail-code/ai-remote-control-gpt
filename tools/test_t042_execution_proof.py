import tempfile
import unittest
from pathlib import Path
import xml.etree.ElementTree as ET
from verify_t042_android_results import REQUIRED,verify_reports
from verify_t042_test_apks import verify_pair

CERT='511567f44b5e527b8b388d0ea2a66ad30725a856ef76670c027b88c25007cd2c'
BASE={'package':'com.example.airemote.dev','version_code':75,'signer_sha256':CERT}

class T042TestApkSignerTests(unittest.TestCase):
    def records(self):
        return dict(BASE),dict(BASE,package=BASE['package']+'.test')
    def test_both_original_signers_accepted(self):
        a,b=self.records();self.assertEqual('ORIGINAL_SIGNER_ON_TARGET_AND_TEST_APK',verify_pair(a,b,BASE)['status'])
    def test_new_target_key_rejected(self):
        a,b=self.records();a['signer_sha256']='0'*64
        with self.assertRaises(ValueError):verify_pair(a,b,BASE)
    def test_new_test_key_rejected(self):
        a,b=self.records();b['signer_sha256']='0'*64
        with self.assertRaises(ValueError):verify_pair(a,b,BASE)
    def test_wrong_target_package_rejected(self):
        a,b=self.records();a['package']='other.app'
        with self.assertRaises(ValueError):verify_pair(a,b,BASE)
    def test_wrong_test_package_rejected(self):
        a,b=self.records();b['package']='other.app.test'
        with self.assertRaises(ValueError):verify_pair(a,b,BASE)
    def test_premature_version_promotion_rejected(self):
        a,b=self.records();a['version_code']=76
        with self.assertRaises(ValueError):verify_pair(a,b,BASE)

class T042AndroidExecutionProofTests(unittest.TestCase):
    def suite(self):
        s=ET.Element('testsuite',tests='9',failures='0',errors='0',skipped='0')
        for c,names in REQUIRED.items():
            for n in sorted(names):ET.SubElement(s,'testcase',classname=c,name=n)
        return s
    def run_report(self,s):
        with tempfile.TemporaryDirectory() as d:
            ET.ElementTree(s).write(Path(d)/'TEST-fixture.xml')
            return verify_reports(d)
    def test_nine_mandatory_executed_tests_required(self):self.assertEqual(9,self.run_report(self.suite())['mandatory_tests'])
    def test_missing_report_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            with self.assertRaises(ValueError):verify_reports(d)
    def test_empty_report_rejected(self):
        with self.assertRaises(ValueError):self.run_report(ET.Element('testsuite',tests='0'))
    def test_missing_required_method_rejected(self):
        s=self.suite();s.remove(s[-1])
        with self.assertRaises(ValueError):self.run_report(s)
    def test_skipped_method_rejected(self):
        s=self.suite();ET.SubElement(s[0],'skipped')
        with self.assertRaises(ValueError):self.run_report(s)
    def test_failed_method_rejected(self):
        s=self.suite();ET.SubElement(s[0],'failure')
        with self.assertRaises(ValueError):self.run_report(s)
    def test_error_method_rejected(self):
        s=self.suite();ET.SubElement(s[0],'error')
        with self.assertRaises(ValueError):self.run_report(s)
    def test_suite_failure_count_rejected(self):
        s=self.suite();s.set('failures','1')
        with self.assertRaises(ValueError):self.run_report(s)
    def test_notrun_status_rejected(self):
        s=self.suite();s[0].set('status','notrun')
        with self.assertRaises(ValueError):self.run_report(s)
    def test_unrelated_tests_do_not_substitute_required_methods(self):
        s=self.suite();s[0].set('classname','unrelated.Test')
        with self.assertRaises(ValueError):self.run_report(s)

if __name__=='__main__':unittest.main()
