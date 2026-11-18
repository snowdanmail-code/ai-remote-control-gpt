"""Fail-closed contracts for independent spectator viewing."""
import unittest
from pathlib import Path
R = Path(__file__).resolve().parents[1]
P = R / "android/app/src/main/java/com/example/airemote"

class SpectatorIsolationContracts(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.card=(P/"OnDeviceCardReader.kt").read_text()
        cls.core=(P/"CardVisionCore.kt").read_text()
        cls.capture=(P/"CaptureService.kt").read_text()
        cls.runtime=(P/"AutonomousAndroidRuntime.kt").read_text()
        cls.ui=(P/"MainActivity.kt").read_text()
        cls.store=(P/"FrameStore.kt").read_text()
        cls.role=(P/"CaptureObservationMode.kt").read_text()

    def test_default_player_and_no_hot_switch(self):
        self.assertIn("role: CaptureRole = CaptureRole.PLAYER",self.role)
        self.assertIn("if (captureActive || !stopped) return false",self.role)
        self.assertIn("OperatorStopStore.stop(this)",self.ui)
        self.assertIn("LiveGamePermissionGate.revoke()",self.ui)

    def test_separate_hole_and_community_readers(self):
        self.assertIn("fun inspectAll(source: Bitmap): OnDeviceTableReading",self.card)
        player=self.card.split("fun inspectAll(source: Bitmap): OnDeviceTableReading",1)[1]
        self.assertIn("CardVisionCore.readCards(raster)",player)
        self.assertNotIn("readVisibleCommunity(",player)
        self.assertIn("fun inspectSpectator(source: Bitmap): VisibleCommunityReading",self.card)
        obs=self.card.split("fun inspectSpectator",1)[1].split("fun inspect(source:",1)[0]
        self.assertNotIn("readCards(",obs)
        self.assertIn("fun readVisibleCommunity(r: VisionRaster)",self.core)
        board=self.core.split("fun readVisibleCommunity(r: VisionRaster)",1)[1].split("fun readCards(",1)[0]
        self.assertNotIn("Не найдены 2 открытые карты игрока",board)

    def test_spectator_read_only_capture_path(self):
        self.assertIn("if (CaptureObservationMode.isSpectator())",self.capture)
        self.assertIn("OnDeviceCardReader.inspectSpectator(screen)",self.capture)
        self.assertIn("OnDeviceCardReader.inspectAll(screen)",self.capture)
        self.assertIn("if (!CaptureObservationMode.isSpectator())",self.capture)
        self.assertIn("FrameStore.spectatorConsensus.accept(",self.capture)
        self.assertNotIn("LiveGamePermissionGate.grant",self.capture)

    def test_never_start_actions_in_spectator_mode(self):
        self.assertIn("if (CaptureObservationMode.isSpectator())",self.runtime)
        self.assertIn("stop(\"Режим наблюдателя: любые игровые действия запрещены\")",self.runtime)
        self.assertIn("if (CaptureObservationMode.isSpectator())",self.ui)
        self.assertIn("return@button",self.ui)
        self.assertIn("spectatorBoardForPreview = null",self.store)

if __name__ == "__main__":
    unittest.main()
