"""Independent reference and integration checks for pure Kotlin Hold'em math."""
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / 'tools'))
from generate_poker_oracle import best, generate, rank_five  # noqa: E402


class PokerMathContract(unittest.TestCase):
    def test_independent_vectors_are_committed_and_current(self):
        vector_file = ROOT / 'tools/fixtures/poker_oracle.tsv'
        self.assertEqual(vector_file.read_text(encoding='utf-8'), generate())

    def test_reference_counts(self):
        self.assertEqual(len(generate().splitlines()), 552)

    def test_ace_low_straight(self):
        self.assertEqual(rank_five('As 2h 3c 4d 5s'.split()), (4, (5,)))

    def test_wheel_straight_flush(self):
        self.assertEqual(rank_five('As 2s 3s 4s 5s'.split()), (8, (5,)))

    def test_royal_flush(self):
        self.assertEqual(rank_five('Ts Js Qs Ks As'.split()), (8, (14,)))

    def test_two_sets_of_trips(self):
        self.assertEqual(best('As Ad Ah Ks Kd Kh 2c'.split()), (6, (14, 13)))

    def test_multi_pair(self):
        self.assertEqual(best('As Ad Ks Kd Qs Qd 2c'.split()), (2, (14, 13, 12)))

    def test_duplicate_rejected(self):
        with self.assertRaises(ValueError):
            best('As As Kd Qd Jd'.split())

    def test_kotlin_module_is_pure_and_guarded(self):
        code = (ROOT / 'android/app/src/main/java/com/example/airemote/PokerMath.kt').read_text()
        self.assertIn('fun evaluateFive(', code)
        self.assertIn('fun evaluate(', code)
        self.assertIn('fun equityVsKnownHands(', code)
        self.assertIn('fun sidePots(', code)
        self.assertIn('fun settleShowdown(', code)
        self.assertIn('ensureUnique(known)', code)
        self.assertNotIn('dispatchGesture', code)
        self.assertNotIn('import android.', code)

    def test_bridge_is_diagnostic_only(self):
        code = (ROOT / 'android/app/src/main/java/com/example/airemote/VerifiedPokerMathBridge.kt').read_text()
        self.assertIn('independentTableHandProof', code)
        self.assertIn('frameIsCurrent', code)
        self.assertIn('HandStage.RIVER -> 5', code)
        self.assertIn('else -> return null', code)
        self.assertNotIn('LiveGestureAccessibilityService', code)

    def test_jvm_test_runner_includes_math(self):
        runner = (ROOT / 'tools/run_jvm_tests.sh').read_text()
        self.assertIn('com.example.airemote.PokerMathScenarios', runner)


if __name__ == '__main__':
    unittest.main()
