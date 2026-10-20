import pathlib
import unittest
ROOT = pathlib.Path(__file__).resolve().parents[1]
SRC = ROOT / 'android/app/src/main/java/com/example/airemote'
class LearningSourceTests(unittest.TestCase):
    def test_transaction(self):
        s = (SRC/'AndroidHandLearningStore.kt').read_text()
        self.assertIn('beginTransaction()',s)
        self.assertIn('setTransactionSuccessful()',s)
        self.assertIn('endTransaction()',s)
    def test_dedup(self):
        s=(SRC/'AndroidHandLearningStore.kt').read_text()
        self.assertIn('PRIMARY KEY(table_key, hand_key)',s)
        self.assertIn('ALREADY_SEEN',s)
    def test_no_network(self):
        for file in ['PostHandLearning.kt','AndroidHandLearningStore.kt']:
            s=(SRC/file).read_text()
            self.assertNotIn('HttpURLConnection',s)
            self.assertNotIn('https://',s)
            self.assertNotIn('127.0.0.1',s)
    def test_stop_does_not_wipe_learned_stats(self):
        s=(SRC/'AndroidHandLearningStore.kt').read_text()
        self.assertNotIn('DROP TABLE',s)
        self.assertNotIn('deleteDatabase',s)
    def test_initialization(self):
        self.assertIn('OnDevicePostHandLearning.initialize', (SRC/'MainActivity.kt').read_text())
        self.assertIn('OnDeviceVerifiedHandCompletion.initialize', (SRC/'CaptureService.kt').read_text())
        self.assertIn('OnDevicePostHandLearning.initialize', (SRC/'AndroidHandLearningStore.kt').read_text())
    def test_learning_only_from_verified_end(self):
        s=(SRC/'PostHandLearning.kt').read_text()
        self.assertIn('terminalConfirmedByIndependentFrames',s)
        self.assertIn('sourceVerified',s)
        self.assertIn('handKey',s)
    def test_no_unverified_automatic_events(self):
        s=(SRC/'CaptureService.kt').read_text()
        self.assertNotIn('onVerifiedHandFinished(',s)
    def test_latest_code_version(self):
        self.assertGreaterEqual(int(__import__('re').search(r'versionCode = (\d+)', (ROOT/'android/app/build.gradle.kts').read_text()).group(1)), 41)
if __name__=='__main__': unittest.main()
