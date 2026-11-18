import pathlib
import unittest

ROOT=pathlib.Path(__file__).resolve().parents[1]
JAVA=ROOT/"android/app/src/main/java/com/example/airemote"
class Code84To85ReportRegression(unittest.TestCase):
    def test_counter_error_fixed(self):
        journal=(JAVA/"PlayerVisionJournal.kt").read_text()
        self.assertIn('if (!reading.complete) inc("incomplete")',journal)
        self.assertNotIn('if (reading.reason.startsWith("CENTERED_")) inc("centered_board_observations")\n            else inc("incomplete")',journal)
    def test_new_revision_is_separate_from_cumulative_code84(self):
        journal=(JAVA/"PlayerVisionJournal.kt").read_text()
        self.assertIn('"revision_v85_only"',journal)
        self.assertIn('root.put("baseline_"+k,root.optLong(k))',journal)
        self.assertIn('"counter_revision","v85"',journal)
    def test_real_capture_uses_separate_fallback_and_keeps_safety(self):
        reader=(JAVA/"OnDeviceCardReader.kt").read_text()
        capture=(JAVA/"CaptureService.kt").read_text()
        self.assertIn('SeparateHeroCardReader.read(adaptiveRaster)',reader)
        self.assertIn('FrameStore.cardConsensus.accept(',capture)
        self.assertIn('OperatorStopStore.isStopped(',capture)
        source=(JAVA/"SeparateHeroCardReader.kt").read_text()
        for f in ('dispatchGesture(', 'ChatGptCloudBridge', 'requestVerifiedAction('):
            self.assertNotIn(f,source)
    def test_decimal_banks_do_not_depend_on_cyrillic_label(self):
        txt=(JAVA/"CenteredTableBoardReader.kt").read_text()
        self.assertIn('val amountAbove: Boolean',txt)
        self.assertIn('val topAmount=valid.any',txt)
        self.assertIn('CenterTableAnchorEvidence(above, below, topAmount)',txt)

if __name__=="__main__":unittest.main()
