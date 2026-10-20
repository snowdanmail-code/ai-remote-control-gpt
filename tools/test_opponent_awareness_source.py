import re
"""Contract tests for opponent learning; no live identity is invented here."""
import pathlib
import unittest
from tools.analyze_incident_report import analyze

ROOT = pathlib.Path(__file__).resolve().parents[1]
MAIN = ROOT / 'android/app/src/main/java/com/example/airemote'

class OpponentAwarenessSourceTests(unittest.TestCase):
    def test_confirmed_two_frame_observations_only(self):
        source = (MAIN/'LiveOpponentAwareness.kt').read_text()
        for required in ('sourceVerified','stableTableAndHandVerified',
                         'actionConfirmedByIndependentFrames','windowGeneration',
                         'minimumFrameGapMs','maximumFrameAgeMs','captureEpoch',
                         'stableOpponentIdentityVerified'):
            self.assertIn(required,source)

    def test_unknown_is_not_negative_vpip_or_pfr(self):
        source = (MAIN/'LiveOpponentAwareness.kt').read_text()
        self.assertIn('voluntarilyEnteredPreflop = if',source)
        self.assertIn('preflopRaised = if',source)
        self.assertIn('true else null',source)
        self.assertIn('showdown',source)

    def test_terminal_only_goes_through_verified_gate(self):
        bridge = (MAIN/'AndroidHandLearningStore.kt').read_text()
        self.assertIn('OnDeviceOpponentAwareness.proposeVerifiedTerminal(frame)',bridge)
        self.assertIn('live.ingest(frame, proposed)',bridge)
        self.assertNotIn('store.commitHandAtomically(', (MAIN/'LiveOpponentAwareness.kt').read_text())
        self.assertNotIn('ingestTrustedObservation(', (MAIN/'CaptureService.kt').read_text())

    def test_stop_erases_transient_only(self):
        stop=(MAIN/'OperatorStopStore.kt').read_text()
        bridge=(MAIN/'AndroidHandLearningStore.kt').read_text()
        self.assertIn('OnDeviceVerifiedHandCompletion.resetTransientEvidence()',stop)
        self.assertIn('OnDeviceOpponentAwareness.resetTransientOnly()',bridge)
        self.assertNotIn('deleteDatabase',stop)

    def test_facing_bet_denominator_excludes_unknown(self):
        sqlite=(MAIN/'AndroidHandLearningStore.kt').read_text()
        self.assertIn('if (facts.foldedWhenFacingBet != null) 1 else 0',sqlite)
        self.assertNotIn('if (facts.facedBet == true) 1 else 0,\n                            if (facts.showdown',sqlite)

    def test_untrusted_incident_identifiers_never_exported(self):
        bridge=(MAIN/'AndroidHandLearningStore.kt').read_text()
        self.assertIn('Never record opponentKey/actionKey/tableKey/handKey',bridge)
        self.assertIn('OPPONENT_CONTEXT_INVALIDATED',bridge)
        output=analyze({'schema':1,'version':'2.14.23','events':[
            {'code':'OPPONENT_CONTEXT_INVALIDATED','sequence':1},
            {'code':'OPPONENT_OBSERVATION_REJECTED','sequence':2},
            {'code':'OPPONENT_HAND_STORAGE_FAILED','sequence':3}]})
        self.assertIn('OPPONENT_CONTEXT_INVALIDATED',output)
        self.assertIn('OPPONENT_HAND_STORAGE_FAILED',output)

    def test_prev_release_kept(self):
        self.assertTrue((ROOT/'QA_v2.14.22.json').exists())
        gradle = (ROOT/'android/app/build.gradle.kts').read_text()
        self.assertRegex(gradle, r'versionName = "2\.14\.\d+[^"]*"')
        self.assertGreaterEqual(int(re.search(r"versionCode\s*=\s*(\d+)", gradle).group(1)), 60)

if __name__=='__main__': unittest.main()
