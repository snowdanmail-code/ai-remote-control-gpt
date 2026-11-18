import pathlib
import unittest
ROOT=pathlib.Path(__file__).resolve().parents[1]
JAVA=ROOT/"android/app/src/main/java/com/example/airemote"
class CenteredBoardSourceChecks(unittest.TestCase):
    def test_player_and_spectator_branches(self):
        t=(JAVA/"OnDeviceCardReader.kt").read_text()
        self.assertIn("CenteredTableBoardReader.read(",t)
        self.assertIn("CenteredPlayerBoardFusion.merge(",t)
        self.assertIn("TableBoardCandidateSelector.choose(",t)
        self.assertIn("centeredRaster(source, raster)",t)
    def test_card_consensus_remains_gated(self):
        capture=(JAVA/"CaptureService.kt").read_text()
        self.assertIn("FrameStore.cardConsensus.accept(",capture)
        self.assertIn("FrameStore.spectatorConsensus.accept(",capture)
        self.assertIn("OperatorStopStore.isStopped(",capture)
    def test_anchor_count_not_game_authority(self):
        t=(JAVA/"CenteredTableBoardReader.kt").read_text()
        for marker in ("dispatchGesture(","requestVerifiedAction(","ChatGptCloudBridge"):
            self.assertNotIn(marker,t)
        j=(JAVA/"PlayerVisionJournal.kt").read_text()
        self.assertIn("CenterTableAnchorText.inspect(positionedLines)",j)
    def test_ocr_and_report(self):
        l=(JAVA/"SpectatorLearningJournal.kt").read_text()
        r=(JAVA/"SpectatorEngineeringReport.kt").read_text()
        self.assertIn('"board_anchor_ocr"',l)
        self.assertIn('"board_anchor_ocr"',r)
        self.assertIn('"player_vision_read_only"',r)
if __name__=="__main__": unittest.main()
