from pathlib import Path
import unittest
P=Path(__file__).resolve().parents[1]/'android/app/src/main/java/com/example/airemote'
class BackgroundOcrIntegrationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.capture=(P/'CaptureService.kt').read_text()
        cls.store=(P/'FrameStore.kt').read_text()
        cls.engine=(P/'OnDeviceAutomaticOcr.kt').read_text()
        cls.dto=(P/'AutomaticOcrEvidence.kt').read_text()
        cls.activity=(P/'MainActivity.kt').read_text()
        cls.auto=(P/'AutonomousAndroidRuntime.kt').read_text()
    def test_capture_consumes_committed_frame(self):
        self.assertIn('OnDeviceAutomaticOcr.offer(applicationContext',self.capture)
        self.assertLess(self.capture.index('FrameStore.latest = FrameInfo('),self.capture.index('OnDeviceAutomaticOcr.offer(applicationContext'))
    def test_no_second_concurrent_recognition(self):
        self.assertIn('if (inFlight ||',self.engine)
        self.assertIn('INTERVAL_MS = 1_500L',self.engine)
        self.assertIn('OnDeviceTextRecognition.inspectStructured',self.engine)
    def test_overtime_and_changed_window_must_fail(self):
        for snippet in ('FrameStore.commitIfCurrent(', 'rootStillMatches()',
                        'OcrFrameProvenance.check(', 'finishedAt - ticket.capturedAtMs in 0L..1_500L',
                        '!OperatorStopStore.isStopped(app)'):
            self.assertIn(snippet,self.engine)
    def test_stop_clears_cached_data(self):
        self.assertIn('lastAutomaticOcr = null',self.store)
        self.assertIn('FrameStore.lastAutomaticOcr = null',self.capture)
        self.assertGreaterEqual(self.capture.count('OnDeviceAutomaticOcr.reset()'),3)
    def test_observation_not_permission(self):
        self.assertIn('val automationReady: Boolean get() = false',self.dto)
        self.assertIn('identityGuard.inspect(null, ticket, agreement',self.dto)
        self.assertNotIn('dispatchGesture(',self.engine)
        self.assertNotIn('LiveGamePermissionGate.grant',self.engine)
        self.assertIn('ПОКАЗАТЬ ФОНОВОЕ OCR',self.activity)
        self.assertIn('OnDeviceAutonomousRuntime.acceptAutomaticDiagnostic(app, it)',self.engine)
        self.assertIn('onVerifiedObservation(if (tiedToCurrent && independentlyBound && permittedByTurn) obs else null, now,', self.auto)
        diagnostic_body = self.auto.split('fun acceptAutomaticDiagnostic(', 1)[1].split('fun acceptVerifiedObservation(', 1)[0]
        self.assertNotIn('onVerifiedObservation(', diagnostic_body)
    def test_stages_from_fresh_verified_sequence_only(self):
        self.assertIn('cards.captureEpoch == ticket.captureEpoch',self.engine)
        self.assertIn('cards.capturedAtMs in (ticket.capturedAtMs - 1_200L)..ticket.capturedAtMs',self.engine)
if __name__=='__main__': unittest.main()
