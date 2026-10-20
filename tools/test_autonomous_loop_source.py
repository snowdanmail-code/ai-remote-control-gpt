"""Non-Android static integration assertions; real phone UI is not tested here."""
import pathlib, unittest
BASE = pathlib.Path(__file__).parents[1]
SRC = BASE/'android/app/src/main/java/com/example/airemote'

class TestAutonomousBridge(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.agent=(SRC/'AutonomousActionLoop.kt').read_text()
        cls.android=(SRC/'AutonomousAndroidRuntime.kt').read_text()
        cls.gesture=(SRC/'LiveGestureAccessibilityService.kt').read_text()
        cls.capture=(SRC/'CaptureService.kt').read_text()
        cls.stop=(SRC/'OperatorStopStore.kt').read_text()
        cls.fresh=(SRC/'LiveDispatchFreshness.kt').read_text()
        cls.activity=(SRC/'MainActivity.kt').read_text()

    def test_no_disabled_gate_bypass(self):
        self.assertIn('tableHandIndependentlyVerified',self.agent)
        self.assertIn('windowIndependentlyVerified',self.agent)
        self.assertIn('view.exactLabelsVerified',self.agent)
    def test_no_automatic_retry(self):
        self.assertIn('usedTurns.add(turnUnique)',self.agent)
        self.assertIn('BLOCKED_UNCERTAIN',self.agent)
    def test_pending_verification(self):
        self.assertIn('transport.observe(view, nowMs)',self.agent)
        self.assertIn('LiveCommandStatus.CONFIRMED',self.agent)
    def test_required_timer(self):
        self.assertIn('requiredRemainingMs',self.agent)
    def test_all_poker_decisions(self):
        self.assertIn('setOf(LiveAction.BET, LiveAction.RAISE)',self.agent)
        self.assertIn('selectedBetChips',self.agent)
    def test_android_broker_uses_real_guarded_dispatch(self):
        self.assertIn('LiveInputCoordinator.requestVerifiedAction(',self.android)
        self.assertIn('OnDeviceLiveActionControl.guard.observe(',self.android)
    def test_android_bound_to_same_frame(self):
        self.assertIn('obs.view.frameSequence == frame.seq',self.android)
        self.assertIn('obs.view.capturedAtMs == frame.capturedAtMs',self.android)
    def test_window_generation_bound_at_os_dispatch(self):
        self.assertIn('area.windowGeneration != command.expectedWindowGeneration',self.gesture)
        self.assertIn('LiveDispatchCheck.WINDOW_CHANGED',self.fresh)
    def test_durable_stop_stops_auto(self):
        self.assertIn('OnDeviceAutonomousRuntime.stop(',self.stop)
    def test_capture_stop_stops_auto(self):
        self.assertIn('OnDeviceAutonomousRuntime.stop(',self.capture)
    def test_accessibility_interrupt_stops_auto(self):
        self.assertIn('OnDeviceAutonomousRuntime.stop(',self.gesture)
    def test_strategy_unconfigured(self):
        self.assertIn('strategy: AutonomousStrategy? = null',self.android)
    def test_capture_does_not_directly_tap(self):
        self.assertNotIn('dispatchVerifiedTap(',self.capture)
    def test_no_external_api_assumptions(self):
        self.assertIn('android.permission.INTERNET', (BASE/'android/app/src/main/AndroidManifest.xml').read_text())
        self.assertNotIn('api.openai.com', self.android)
    def test_operator_diagnostic(self):
        self.assertIn('ПРОВЕРИТЬ ГОТОВНОСТЬ АВТО-РЕЖИМА',self.activity)

if __name__=='__main__': unittest.main()
