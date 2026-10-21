"""Static source/manifest contract only. This does NOT run Android or send gestures."""
import pathlib, unittest, xml.etree.ElementTree as ET
BASE=pathlib.Path(__file__).parents[1]
APP=BASE/'android/app/src/main'
SRC=APP/'java/com/example/airemote'
A='{http://schemas.android.com/apk/res/android}'

class TestLiveAndroidTransport(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.manifest=ET.parse(APP/'AndroidManifest.xml').getroot()
        cls.service=(SRC/'LiveGestureAccessibilityService.kt').read_text()
        cls.safety=(SRC/'LiveTapSafety.kt').read_text()
        cls.stop=(SRC/'OperatorStopStore.kt').read_text()
        cls.activity=(SRC/'MainActivity.kt').read_text()
        cls.capture=(SRC/'CaptureService.kt').read_text()

    def test_manifest_permission(self):
        x=self.manifest.find("application/service[@"+A+"name='.LiveGestureAccessibilityService']")
        self.assertIsNotNone(x)
        self.assertEqual(x.get(A+'permission'),'android.permission.BIND_ACCESSIBILITY_SERVICE')
    def test_manifest_metadata(self):
        x=self.manifest.find("application/service[@"+A+"name='.LiveGestureAccessibilityService']/meta-data")
        self.assertIsNotNone(x)
        self.assertEqual(x.get(A+'resource'),'@xml/live_input_accessibility')
    def test_service_gesture_capability(self):
        x=ET.parse(APP/'res/xml/live_input_accessibility.xml').getroot()
        self.assertEqual(x.get(A+'canPerformGestures'), 'true')
    def test_service_window_capability(self):
        x=ET.parse(APP/'res/xml/live_input_accessibility.xml').getroot()
        self.assertEqual(x.get(A+'canRetrieveWindowContent'), 'true')
    def test_internal_gesture_uses_public_android_api(self):
        self.assertIn('dispatchGesture(gesture',self.service)
    def test_just_one_short_tap(self):
        self.assertIn('GestureDescription.StrokeDescription(path, 0L, 65L)', self.service)
    def test_verified_dispatch(self):
        self.assertIn('OnDeviceLiveActionControl.guard.claimDispatch(command.id)',self.service)
    def test_durable_stop_just_before_dispatch(self):
        self.assertIn('OperatorStopStore.isStopped(applicationContext)', self.service)
    def test_target_package_proof(self):
        self.assertIn('rootInActiveWindow?.packageName?.toString()', self.service)
    def test_explicit_platform_permission(self):
        self.assertIn('LiveGamePermissionGate.permitted()',self.service)
    def test_real_game_result_required_separately(self):
        self.assertIn('Wait for a separate fresh, verified game-state observation.',self.service)
    def test_movable_stop_overlay(self):
        self.assertIn('TYPE_ACCESSIBILITY_OVERLAY',self.service)
        self.assertIn('wm.updateViewLayout(view, layout)',self.service)
    def test_overlay_durable_stop(self):
        self.assertIn('OperatorStopStore.stop(applicationContext)',self.service)
        self.assertIn('stopService(Intent(this, CaptureService::class.java))',self.service)
    def test_version(self):
        self.assertIn('AI Remote Control v${packageManager.getPackageInfo(packageName, 0).versionName}', self.activity)
        self.assertRegex((BASE/'android/app/build.gradle.kts').read_text(), r'versionCode = (?:4[3-9]|[5-9][0-9])')
    def test_attribution(self):
        self.assertIn('Разработано в лаборатории Сноудена',self.activity)
    def test_insets(self):
        self.assertIn('WindowInsets.Type.systemBars()', self.activity)
    def test_no_server_fields(self):
        for x in ('127.0.0.1','ARC_DEVICE_TOKEN','http://','URL сервера'):
            self.assertNotIn(x,self.activity)
    def test_no_model_network_calls(self):
        self.assertIn('android.permission.INTERNET', (APP/'AndroidManifest.xml').read_text())
        self.assertNotIn('api.openai.com', self.service)
    def test_operator_arm_updates_volatile_guard(self):
        self.assertIn('OnDeviceLiveActionControl.guard.armByOperator()',self.stop)
    def test_operator_stop_revokes_permission(self):
        self.assertIn('LiveGamePermissionGate.revoke()',self.stop)
    def test_capture_requires_stopped_gate_but_does_not_rearm(self):
        self.assertIn('require(!OperatorStopStore.isStopped(applicationContext) &&',self.capture)
        self.assertNotIn('OnDeviceLiveActionControl.guard.armByOperator()',self.capture)
    def test_no_automatic_ui_taps(self):
        self.assertNotIn('requestVerifiedAction(',self.capture)
        self.assertNotIn('requestVerifiedAction(',self.activity)

if __name__=='__main__':unittest.main()
