"""Regression tests for minimal operator UI and fail-closed Android signing."""
import re
import unittest
from pathlib import Path

R = Path(__file__).resolve().parents[1]
MAIN = R/'android/app/src/main/java/com/example/airemote/MainActivity.kt'
BUILD = R/'android/app/build.gradle.kts'
WORKFLOW = R/'.github/workflows/android.yml'


class MinimalUiAndReleaseTest(unittest.TestCase):
    def test_only_four_primary_work_actions(self):
        s = MAIN.read_text()
        m = re.search(r'val primaryActions = setOf\((.*?)\)\s*fun label', s, flags=re.S)
        self.assertIsNotNone(m)
        actions = re.findall(r'"([A-ZА-Я0-9 (),-]+)"', m.group(1))
        self.assertEqual(4, len(actions), actions)
        self.assertTrue(any('ВОЙТИ ЧЕРЕЗ CHATGPT' in a for a in actions))
        self.assertTrue(any('ЗАХВАТ' in a for a in actions))
        self.assertTrue(any('ИИ-ИГРОКА' in a for a in actions))
        self.assertIn('СТОП — ПРЕКРАТИТЬ ВСЕ ДЕЙСТВИЯ', s)

    def test_advanced_is_actually_collapsed(self):
        s = MAIN.read_text()
        self.assertIn('val advanced = LinearLayout', s)
        self.assertIn('visibility = android.view.View.GONE', s)
        self.assertIn('advanced.visibility = if (show)', s)
        self.assertIn('content.addView(advanced)', s)
        self.assertIn('advanced.addView(arm', s)
        self.assertIn('val target = if (text in primaryActions) content else advanced', s)

    def test_keystore_is_not_committed(self):
        s = BUILD.read_text()
        self.assertIn('AI_REMOTE_SIGNING_KEYSTORE_PATH', s)
        self.assertIn('signingConfig = signingConfigs.getByName("existingInstallation")', s)
        self.assertIn('applicationIdSuffix = ".dev"', s)
        self.assertIn('versionCode = 75', s)
        self.assertNotIn('storePassword = "android"', s)
        self.assertNotIn('keyPassword = "android"', s)

    def test_workflow_no_unverified_artifact(self):
        w = WORKFLOW.read_text()
        self.assertIn('secrets.EXISTING_APK_KEYSTORE_B64', w)
        self.assertIn('tools/reference_prior_apk_v2.14.41.json', w)
        self.assertIn('tools/verify_android_upgrade.py', w)
        self.assertIn('app:assembleDebug', w)
        self.assertIn('app:testDebugUnitTest', w)
        self.assertLess(w.index('verify_android_upgrade.py'), w.index('actions/upload-artifact@v4'))
        self.assertIn('BLOCKED: prior signing keystore missing', w)


if __name__ == '__main__':
    unittest.main()
