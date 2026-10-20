from pathlib import Path
import unittest
R=Path(__file__).resolve().parents[1]
class SourceGateTest(unittest.TestCase):
 def test_no_bypass(self):
  live=(R/'android/app/src/main/java/com/example/airemote/LiveGestureAccessibilityService.kt').read_text()
  runtime=(R/'android/app/src/main/java/com/example/airemote/AutonomousAndroidRuntime.kt').read_text()
  guard=(R/'android/app/src/main/java/com/example/airemote/VerifiedDispatchAdmission.kt').read_text()
  self.assertIn('OnDeviceVerifiedDispatchAdmission.gate.claim(view, tap, desired, nowMs',live)
  self.assertIn('OnDeviceVerifiedDispatchAdmission.gate.admit(obs.view, obs.foregroundPackage',runtime)
  self.assertIn('OnDeviceVerifiedDispatchAdmission.clear()',runtime)
  self.assertIn('val readyForNewDecision = loop.status().state != AutoLoopState.WAITING_FOR_RESULT',runtime)
  self.assertIn('current = null',guard)
  self.assertIn('trustedExternalBinding',guard)
 def test_metadata(self):
  self.assertIn('versionName = "2.14.41-',(R/'android/app/build.gradle.kts').read_text())
if __name__=='__main__':unittest.main()
