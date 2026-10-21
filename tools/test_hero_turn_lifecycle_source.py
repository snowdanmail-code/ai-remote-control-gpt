import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
CORE = ROOT / 'android/app/src/main/java/com/example/airemote'

class HeroTurnLifecycleSourceTests(unittest.TestCase):
    def test_core_is_pure_and_read_only(self):
        src = (CORE / 'HeroTurnLifecycleDiagnostics.kt').read_text()
        for banned in ('dispatchGesture', 'VerifiedTapArea(', 'LiveGamePermissionGate.arm(',
                       'AutonomousObservation(', 'grantPermission', 'android.'):
            self.assertNotIn(banned, src)
        self.assertIn('val authorizesTap: Boolean get() = false', src)
        self.assertIn('val confirmsGameTurn: Boolean get() = false', src)

    def test_capture_consumes_actual_turn_signal(self):
        capture = (CORE / 'CaptureService.kt').read_text()
        self.assertIn('heroTurnLifecycle.inspect(', capture)
        self.assertIn('turnSignal, origin.observedPackage', capture)
        self.assertIn('origin.windowGeneration, origin.safeForLocalDiagnostics', capture)
        self.assertIn('OperatorStopStore.isStopped(applicationContext)', capture)

    def test_stop_occlusion_clear_visual_episode(self):
        store = (CORE / 'FrameStore.kt').read_text()
        self.assertGreaterEqual(store.count('heroTurnLifecycle.reset()'), 2)
        self.assertGreaterEqual(store.count('lastHeroTurnEpisode = null'), 2)
        self.assertIn('FrameStore.invalidateOccludedSurface', (CORE / 'CaptureService.kt').read_text())

    def test_ui_is_diagnostic_not_autonomous_permission(self):
        ui = (CORE / 'MainActivity.kt').read_text()
        self.assertIn('ДИАГНОСТИКА НАЧАЛА И ОКОНЧАНИЯ ХОДА', ui)
        self.assertIn('lastHeroTurnEpisode?.summary()', ui)
        self.assertRegex(ui, r'v2\.14\.\d+')

    def test_version_and_build_name(self):
        gradle = (ROOT/'android/app/build.gradle.kts').read_text()
        workflow = (ROOT/'.github/workflows/android.yml').read_text()
        self.assertGreaterEqual(int(re.search(r'versionCode\s*=\s*(\d+)',gradle).group(1)),65)
        self.assertRegex(gradle, r'versionName = \"2\.14\.\d+[^\"]*\"')
        self.assertIn('AI-Remote-Control-GPT-T042-Capture-Samsung-Code78.apk', workflow)

if __name__=='__main__': unittest.main()
