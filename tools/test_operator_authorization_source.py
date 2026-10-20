"""Source-contract checks for the Android operator-only automation switch.

Not an Android runtime test. The Kotlin behavioral checks are in the companion
OperatorAutomationAuthorizationScenarios.kt file.
"""
from pathlib import Path
import unittest

BASE = Path(__file__).resolve().parents[1] / 'android/app/src/main/java/com/example/airemote'

class OperatorAuthorizationSourceTest(unittest.TestCase):
    def test_user_has_real_permission_switch(self):
        src = (BASE / 'MainActivity.kt').read_text()
        self.assertIn('РАЗРЕШИТЬ КАСАНИЯ ДЛЯ ПОДТВЕРЖДЁННОГО ПРИЛОЖЕНИЯ', src)
        self.assertIn('requestAutomationAuthorization()', src)
        self.assertIn('Разрешаю управление', src)
        self.assertIn('OperatorAutomationAuthorization.assess(request)', src)
        self.assertIn('LiveGamePermissionGate.grantForVerifiedEnvironment(', src)

    def test_stop_revokes_authorization(self):
        stop = (BASE / 'OperatorStopStore.kt').read_text()
        runtime = (BASE / 'AutonomousAndroidRuntime.kt').read_text()
        self.assertIn('LiveGamePermissionGate.revoke()', stop)
        self.assertIn('LiveGamePermissionGate.revoke()', runtime)

    def test_ocr_does_not_forge_verified_hand(self):
        src = (BASE / 'AutonomousAndroidRuntime.kt').read_text()
        self.assertIn('onVerifiedObservation(if (tiedToCurrent && independentlyBound && permittedByTurn) obs else null, now,', src)
        diagnostic_body = src.split('fun acceptAutomaticDiagnostic(', 1)[1].split('fun acceptVerifiedObservation(', 1)[0]
        self.assertNotIn('onVerifiedObservation(', diagnostic_body)
        self.assertIn('tableHandIndependentlyVerified', (BASE / 'AutonomousActionLoop.kt').read_text())

    def test_gesture_transport_still_checks_permission(self):
        src = (BASE / 'LiveGestureAccessibilityService.kt').read_text()
        self.assertIn('LiveGamePermissionGate.permitted()', src)
        self.assertIn('OperatorStopStore.isStopped(context)', src)

    def test_authorization_requires_recent_non_system_app(self):
        src = (BASE / 'OperatorAutomationAuthorization.kt').read_text()
        self.assertIn('MAX_RECENT_PACKAGE_AGE_MS: Long = 20_000L', src)
        self.assertIn('target == request.ownPackage', src)
        self.assertIn('operatorConfirmedPlatformAllowsAutomation', src)

if __name__ == '__main__': unittest.main()
