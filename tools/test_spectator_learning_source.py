"""Source-level safety contracts for local spectator error/example collection."""
import unittest
from pathlib import Path
P = Path(__file__).resolve().parents[1] / "android/app/src/main/java/com/example/airemote"

class SpectatorLearningContracts(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.store=(P/"SpectatorLearningJournal.kt").read_text()
        cls.cap=(P/"CaptureService.kt").read_text()
        cls.ui=(P/"MainActivity.kt").read_text()
        cls.player=(P/"OnDeviceCardReader.kt").read_text()
        cls.runtime=(P/"AutonomousAndroidRuntime.kt").read_text()

    def test_player_reader_unaffected(self):
        self.assertIn("fun inspectAll(source: Bitmap): OnDeviceTableReading",self.player)
        seated=self.player.split("fun inspectAll(source: Bitmap): OnDeviceTableReading",1)[1]
        self.assertIn("CardVisionCore.readCards(raster)",seated)
        self.assertNotIn("SpectatorLearningJournal",self.player)
        self.assertIn("if (CaptureObservationMode.isSpectator())",self.runtime)

    def test_journal_is_read_only_private_and_bounded(self):
        self.assertIn('private const val LIMIT = 80',self.store)
        self.assertIn('getSharedPreferences(PREFS, Context.MODE_PRIVATE)',self.store)
        self.assertIn('if (!CaptureObservationMode.isSpectator()) return',self.store)
        self.assertIn('operatorLabelUnrecognized(',self.store)
        self.assertIn('operatorFeedback(',self.store)
        self.assertIn('operator_confirmed',self.store)
        self.assertIn('operator_corrected',self.store)
        self.assertIn('two_frame_agreements',self.store)
        self.assertIn('candidate_changes',self.store)
        self.assertIn('is_ground_truth',self.store)
        for forbidden in ('dispatchGesture(', 'HttpURLConnection', 'MediaProjection', 'getPixel(', 'ChatGptCloudBridge', 'grantForVerifiedEnvironment'):
            self.assertNotIn(forbidden,self.store)

    def test_observations_only_after_atomic_commit_without_lock(self):
        self.assertIn('val published = FrameStore.commitIfCurrent(',self.cap)
        self.assertIn('if (published && publishedSequence > 0L',self.cap)
        self.assertIn('SpectatorLearningJournal.observe(applicationContext',self.cap)
        self.assertIn('CaptureObservationMode.isSpectator()',self.cap)
        self.assertIn('OperatorStopStore.isStopped(applicationContext)',self.cap)

    def test_explicit_feedback_and_export_only(self):
        self.assertIn('reviewSpectatorExample()',self.ui)
        self.assertIn('labelUnrecognizedSpectatorBoard()',self.ui)
        self.assertIn('SpectatorBoardLabels.parse(',self.ui)
        self.assertIn('SpectatorLearningJournal.operatorFeedback(',self.ui)
        self.assertIn('SpectatorLearningJournal.operatorLabelUnrecognized(',self.ui)
        self.assertIn('SpectatorLearningJournal.exportJson(this)',self.ui)
        self.assertIn('Intent.ACTION_CREATE_DOCUMENT',self.ui)

if __name__ == '__main__':
    unittest.main()
