from pathlib import Path
import unittest
root=Path(__file__).resolve().parents[1]
class VerifyEffectSource(unittest.TestCase):
 def test_no_gesture_callback_as_success(self):
  guard=(root/'android/app/src/main/java/com/example/airemote/LiveGameActionGuard.kt').read_text()
  source=(root/'android/app/src/main/java/com/example/airemote/AuthenticatedActionEffects.kt').read_text()
  self.assertIn('effectTicketVerifier(ticket, command, view, nowMs)',guard)
  self.assertIn('val verifiedEffectTicket: VerifiedEffectTicket? = null',guard)
  self.assertNotIn('view.stateFingerprint != command.stateFingerprint && view.actionEffectVerified',guard)
  self.assertIn('old.proofId == proof.proofId',source)
  self.assertIn('old.potAfter != proof.potAfter',source)
  self.assertIn('provider.verify(proof)',source)
 def test_version(self):
  self.assertRegex((root/'android/app/build.gradle.kts').read_text(), r'versionName = \"2\.14\.\d+-')
if __name__=='__main__': unittest.main()
