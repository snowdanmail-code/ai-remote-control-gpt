import pathlib
import unittest

ROOT=pathlib.Path(__file__).resolve().parents[1]
JAVA=ROOT / "android/app/src/main/java/com/example/airemote"
class RealCaptureModelBridgeTests(unittest.TestCase):
    def test_source_is_real_frame(self):
        x=(JAVA/"OnDeviceAutomaticOcr.kt").read_text()
        self.assertIn("LivePlayerModelContextAssembler.fromFrame(",x)
        self.assertIn("OnDevicePlayerContextBuffer.memory.offer(sample)",x)
    def test_stop_erases_buffer(self):
        for name in ["AutonomousAndroidRuntime.kt","FrameStore.kt"]:
            self.assertIn("OnDevicePlayerContextBuffer.memory.clear()", (JAVA/name).read_text())
    def test_model_is_only_asked_on_user_action(self):
        ui=(JAVA/"MainActivity.kt").read_text()
        ocr=(JAVA/"OnDeviceAutomaticOcr.kt").read_text()
        self.assertIn("АНАЛИЗ ИГРЫ В CHATGPT — ТЕСТ",ui)
        self.assertNotIn("ChatGptCloudBridge.askPrompt(",ocr)
    def test_source_does_not_authorize_actions(self):
        x=(JAVA/"LivePlayerModelContext.kt").read_text()
        self.assertIn("game_identity_verified",x)
        self.assertIn("action_authorized",x)
        self.assertIn("MAX_FRAME_AGE_MS",x)
if __name__=="__main__":unittest.main()
