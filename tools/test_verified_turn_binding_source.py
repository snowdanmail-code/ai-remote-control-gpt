from pathlib import Path
import unittest
ROOT=Path(__file__).resolve().parents[1]
class BindingSourceTest(unittest.TestCase):
    def test_producer_required(self):
        src=(ROOT/'android/app/src/main/java/com/example/airemote/VerifiedTurnBinding.kt').read_text()
        for phrase in ['ExternalAttestationVerifier','!verifier.verify(proof)','proof.proofId == old.proofId','proof.handId != view.handKey','val authorizesTap: Boolean get() = false','CaptureViewport','!rotated']:
            self.assertIn(phrase,src)
    def test_android_metadata(self):
        src=(ROOT/'android/app/build.gradle.kts').read_text()
        self.assertRegex(src, r'versionName = "2\.14\.\d+-')
if __name__=='__main__': unittest.main()
