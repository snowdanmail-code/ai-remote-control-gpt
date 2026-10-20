import unittest
from pathlib import Path

BASE = Path(__file__).resolve().parents[1]
SOURCE = BASE / 'android/app/src/main'
KOTLIN = SOURCE / 'java/com/example/airemote'

class AndroidLiteIsolationTests(unittest.TestCase):
    def test_no_network_permission(self):
        self.assertIn('android.permission.INTERNET', (SOURCE/'AndroidManifest.xml').read_text())
        self.assertFalse(any('http' in p.read_text() for p in [KOTLIN/'CaptureService.kt', KOTLIN/'VerifiedLocalPokerStrategy.kt']))

    def test_no_unwanted_server_network_code(self):
        source = '\n'.join(p.read_text() for p in KOTLIN.glob('*.kt') if p.name not in ('ChatGptCloudBridge.kt', 'ChatGptAuthProtocol.kt'))
        for forbidden in ('HttpURLConnection', '127.0.0.1', '8787', 'vision/cards', 'EXTRA_BASE_URL', 'EXTRA_TOKEN', 'ACTION_POKER_START'):
            self.assertNotIn(forbidden, source, forbidden)

    def test_ui_has_no_obsolete_settings(self):
        source = (KOTLIN/'MainActivity.kt').read_text()
        for forbidden in ('Секрет устройства', 'URL сервера', 'Введите карты вручную', 'РАСПОЗНАТЬ КАРТЫ С КАДРА', 'БЫСТРЫЙ тест:'):
            self.assertNotIn(forbidden, source)

    def test_branding_above_stop_but_not_scroll_area(self):
        activity = (KOTLIN/'MainActivity.kt').read_text()
        caption = 'Разработано в лаборатории Сноудена'
        self.assertEqual(activity.count(caption), 1)
        caption_position = activity.index('root.addView(laboratoryCaption')
        stop_position = activity.index('root.addView(stop')
        scroll_position = activity.index('root.addView(scroll')
        self.assertLess(caption_position, stop_position)
        self.assertLess(stop_position, scroll_position)

    def test_no_network_requests_or_timers(self):
        source = '\n'.join(p.read_text() for p in KOTLIN.glob('*.kt') if p.name not in ('ChatGptCloudBridge.kt', 'ChatGptAuthProtocol.kt'))
        self.assertNotIn('OkHttpClient', source)
        self.assertNotIn('HttpURLConnection', source)
        self.assertNotIn('ScheduledExecutorService', source)
        self.assertNotIn('api.openai.com', source)

    def test_stop_gate_persisted(self):
        activity = (KOTLIN/'MainActivity.kt').read_text()
        service = (KOTLIN/'CaptureService.kt').read_text()
        store = (KOTLIN/'OperatorStopStore.kt').read_text()
        self.assertIn('OperatorStopStore.stop(this)', activity)
        self.assertIn('OperatorStopStore.isStopped(this)', activity)
        self.assertIn('OperatorStopStore.stop(this)', service)
        self.assertIn('OperatorStopStore.isStopped(this)', service)
        self.assertIn('commit()', store)

    def test_stop_not_obscured_by_status_bar(self):
        activity = (KOTLIN/'MainActivity.kt').read_text()
        self.assertIn('setOnApplyWindowInsetsListener', activity)
        self.assertIn('WindowInsets.Type.systemBars()', activity)

    def test_only_operator_accessibility_service_in_apk(self):
        manifest = (SOURCE/'AndroidManifest.xml').read_text()
        self.assertIn('android.permission.BIND_ACCESSIBILITY_SERVICE', manifest)
        self.assertNotIn('<uses-permission android:name="android.permission.BIND_ACCESSIBILITY_SERVICE"', manifest)
        self.assertNotIn('DemoAccessibilityService', manifest)

    def test_future_login_not_faked(self):
        activity = (KOTLIN/'MainActivity.kt').read_text()
        self.assertIn('ChatGptCloudBridge.startSignIn', activity)
        self.assertIn('ChatGptCloudBridge.testInference', activity)
        self.assertNotIn('ВОЙТИ В CHATGPT ПО ПАРОЛЮ', activity)

    def test_actual_capture_ocr_still_present(self):
        service = (KOTLIN/'CaptureService.kt').read_text()
        self.assertIn('MediaProjectionManager', service)
        self.assertIn('FrameStore.latest = FrameInfo', service)
        self.assertTrue((KOTLIN/'OnDeviceTextRecognition.kt').exists())

if __name__ == '__main__': unittest.main()
