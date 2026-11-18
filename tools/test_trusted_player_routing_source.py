import pathlib
import unittest

ROOT=pathlib.Path(__file__).resolve().parents[1]
JAVA=ROOT / "android/app/src/main/java/com/example/airemote"

class TrustedPlayerRoutingWiringTests(unittest.TestCase):
    def test_runtime_allows_both_verified_routes_without_double_registration(self):
        runtime=(JAVA / "AutonomousAndroidRuntime.kt").read_text()
        self.assertIn("TrustedPlayerInputPolicy.route(",runtime)
        self.assertIn("OnDeviceAuthorizedPlayerCycle.providerConnected()",runtime)
        self.assertIn("OnDeviceAuthorizedPlayerFeed.available()",runtime)
        self.assertIn("TrustedPlayerInputRoute.CONFLICT",runtime)
    def test_stop_revokes_both_provider_routes_and_identity(self):
        runtime=(JAVA / "AutonomousAndroidRuntime.kt").read_text()
        self.assertIn("OnDeviceAuthorizedPlayerCycle.clear(reason)",runtime)
        self.assertIn("OnDeviceAuthorizedPlayerFeed.invalidateOnStop()",runtime)
        self.assertIn("identityBinding = null",runtime)
    def test_registration_requires_stop_and_no_capture(self):
        feed=(JAVA / "OnDeviceAuthorizedPlayerFeed.kt").read_text()
        self.assertIn("if (!OperatorStopStore.isStopped(context) || FrameStore.isCapturing) return false",feed)
        self.assertIn("guard.hasUnconfirmedAction()",feed)
        self.assertIn("signedProvider.verifyFullTurnState(packet)",feed)

if __name__ == "__main__":
    unittest.main()
