import unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
SRC=ROOT/'android/app/src/main/java/com/example/airemote'
class CardVisionIntegrationChecks(unittest.TestCase):
    def test_pixel_reader_pure_kotlin(self):
        core=(SRC/'CardVisionCore.kt').read_text()
        self.assertIn('fun readCards(',core)
        self.assertIn('fun classifySuit(',core)
        self.assertIn('fun classifyRank(',core)
        self.assertIn('checkUnique(',core)
        self.assertIn('val community:',core)
        self.assertIn('Неизвестный номинал/масть',core)
    def test_service_does_not_upload(self):
        service=(SRC/'CaptureService.kt').read_text()
        self.assertIn('OnDeviceCardReader.inspectAll(screen)',service)
        self.assertIn('lastCardInspectionAt >= 1_350L',service)
        self.assertNotIn('HttpURLConnection',service)
    def test_cards_behind_visibility_and_stop(self):
        service=(SRC/'CaptureService.kt').read_text()
        self.assertIn('AppVisibilityGate.canPublish(visibilityTicket)',service)
        self.assertIn('OperatorStopStore.isStopped(applicationContext)',service)
        self.assertIn('captureEpoch == FrameStore.captureEpoch',service)
    def test_card_cache_reset_on_stop(self):
        store=(SRC/'FrameStore.kt').read_text()
        self.assertIn('cardConsensus.reset()',store)
        self.assertIn('lastConfirmedForPreview = null',store)
        self.assertIn('FrameStore.clear()', (SRC/'MainActivity.kt').read_text())
    def test_source_not_asserted_by_detector(self):
        main=(SRC/'MainActivity.kt').read_text()
        self.assertIn('Источник приложения не подтверждён',main)
        self.assertNotIn('OnDeviceHandState.tracker.ingest(',main)
        self.assertIn('Фаза раздачи: НЕ ПОДТВЕРЖДЕНА', main)
    def test_no_remote_access(self):
        manifest=(ROOT/'android/app/src/main/AndroidManifest.xml').read_text()
        self.assertIn('android.permission.INTERNET',manifest)
        self.assertIn('android.permission.BIND_ACCESSIBILITY_SERVICE',manifest)
        self.assertNotIn('<uses-permission android:name="android.permission.BIND_ACCESSIBILITY_SERVICE"', manifest)
    def test_brand_and_system_padding(self):
        main=(SRC/'MainActivity.kt').read_text()
        self.assertIn('Разработано в лаборатории Сноудена',main)
        self.assertIn('WindowInsets.Type.systemBars()', main)
        self.assertIn('СТОП — ПРЕКРАТИТЬ ВСЕ ДЕЙСТВИЯ',main)
    def test_version_monotonic(self):
        gradle=(ROOT/'android/app/build.gradle.kts').read_text()
        self.assertGreaterEqual(int(__import__('re').search(r'versionCode = (\d+)', gradle).group(1)), 39)
        self.assertIn('versionName = "2.14.',gradle)
    def test_two_frame_consent(self):
        source=(SRC/'CardReadingConsistency.kt').read_text()
        self.assertIn('requiredSamples: Int = 2',source)
        self.assertIn('maximumSampleAgeMs: Long = 1_200L',source)
        self.assertIn('!signal.reading.complete',source)
if __name__=='__main__':unittest.main()
