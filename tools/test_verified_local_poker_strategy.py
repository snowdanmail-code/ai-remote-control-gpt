"""Regression checks for the local decision provider; not a live action or APK test."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'android/app/src/main/java/com/example/airemote'

class VerifiedLocalStrategySourceTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.strategy = (SRC / 'VerifiedLocalPokerStrategy.kt').read_text()
        cls.runtime = (SRC / 'AutonomousAndroidRuntime.kt').read_text()
        cls.activity = (SRC / 'MainActivity.kt').read_text()
        cls.loop = (SRC / 'AutonomousActionLoop.kt').read_text()

    def test_strategy_is_local_and_does_not_tap_or_call_network(self):
        self.assertIn('class VerifiedLocalPokerStrategy : AutonomousStrategy', self.strategy)
        for forbidden in ('dispatchGesture(', 'requestVerifiedAction(', 'openConnection(', 'http://', 'https://'):
            self.assertNotIn(forbidden, self.strategy)

    def test_strategy_bound_to_exact_turn_and_frame(self):
        for expected in ('turnId == observation.turnId',
                         'captureEpoch == observation.view.captureEpoch',
                         'frameSequence == observation.view.frameSequence'):
            self.assertIn(expected, self.strategy)

    def test_equity_not_guessed_from_unverified_cards(self):
        self.assertIn('externallyVerified && independentSampleCount >= (if (exactEnumeration) 1 else 200)', self.strategy)
        self.assertIn('if (equity == null)', self.strategy)

    def test_bet_bounds_required(self):
        self.assertIn('minBet != null && maxBet != null', self.strategy)
        self.assertIn('amount in minBet..maxBet', self.strategy)

    def test_gpt_only_strategy_installed_without_auto_arming(self):
        self.assertIn('ensureGptOnlyStrategy()', self.activity)
        self.assertIn('strategy = ValidatedPlayerStrategy(', self.runtime)
        self.assertIn('local = AutonomousStrategy { null }', self.runtime)
        router = (SRC / 'ValidatedPlayerStrategy.kt').read_text()
        self.assertIn('return localDecision', router)
        self.assertIn('PlayerModelDecisionGuard.inspect', router)
        self.assertIn('OperatorStopStore.isStopped(context)', self.runtime)
        self.assertIn('LiveGamePermissionGate.permitted()', self.runtime)

    def test_gpt_auto_blocks_missing_source_without_starting_local_check(self):
        start = self.runtime.split('fun startIfAuthorized(', 1)[1].split('fun ', 1)[0]
        no_source = start.split('if (identityBinding == null) {', 1)[1].split('}', 1)[0]
        self.assertIn('AutoLoopState.BLOCKED_CONTEXT', no_source)
        self.assertIn('return false', no_source)
        self.assertNotIn('LocalPlayerCheckPilot.start(', start)

    def test_missing_verified_observation_not_promoted(self):
        self.assertIn('onVerifiedObservation(if (tiedToCurrent && independentlyBound && permittedByTurn) obs else null, now,', self.runtime)
        diagnostic_body = self.runtime.split('fun acceptAutomaticDiagnostic(', 1)[1].split('fun acceptVerifiedObservation(', 1)[0]
        self.assertNotIn('onVerifiedObservation(', diagnostic_body)
        self.assertIn('tableHandIndependentlyVerified', self.loop)

    def test_virtual_chip_assumption_is_documented_not_money_detection(self):
        self.assertIn('виртуальных фишек', self.activity)
        self.assertNotIn('currency sign permits', self.strategy)

if __name__ == '__main__': unittest.main()
