import pathlib, unittest
ROOT = pathlib.Path(__file__).resolve().parents[1]
SRC = ROOT/'android/app/src/main/java/com/example/airemote'
class TerminalSourceTests(unittest.TestCase):
    def test_no_fake_capture_producer(self):
        capture=(SRC/'CaptureService.kt').read_text()
        self.assertNotIn('ingestTrustedObservation(',capture)
        self.assertNotIn('onVerifiedHandFinished(',capture)
    def test_durable_stop_checked_at_bridge(self):
        s=(SRC/'AndroidHandLearningStore.kt').read_text()
        self.assertIn('OperatorStopStore.isStopped(context)',s)
        self.assertIn('live.reset()',s)
    def test_capture_end_clears_only_transient_evidence(self):
        s=(SRC/'CaptureService.kt').read_text()
        self.assertIn('OnDeviceVerifiedHandCompletion.resetTransientEvidence()',s)
        self.assertNotIn('deleteDatabase(',s)
    def test_strict_terminal_cue_and_stable_id(self):
        s=(SRC/'VerifiedHandCompletionGate.kt').read_text()
        self.assertIn('stableHandKeyVerified',s)
        self.assertIn('terminalCueVerified',s)
        self.assertIn('minimumIndependentGapMs',s)
    def test_no_actions_and_no_external_network(self):
        s=(SRC/'VerifiedHandCompletionGate.kt').read_text()
        for forbidden in ('dispatchGesture','HttpURLConnection','https://','performClick('):
            self.assertNotIn(forbidden,s)
if __name__=='__main__': unittest.main()
