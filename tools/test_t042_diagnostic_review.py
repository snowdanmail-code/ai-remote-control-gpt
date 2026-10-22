import sys
import unittest
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
from t042_diagnostic_review import render, compare, normalized

class EngineeringReviewTest(unittest.TestCase):
    def make(self, n=21, unknown=10, stable=0, reviewed=0):
        return {
            "schema": "t042-engineering-diagnostics-v1",
            "provenance": {"app_version": "2.14.41-code79"},
            "assurance": {"spectator_gestures_allowed": False},
            "quality_kpis": {
                "frame_inspections": n, "ambiguous": unknown,
                "two_frame_agreements": stable,
                "human_reviewed_recognized": reviewed,
                "human_corrected": 0,
            },
            "prioritized_issues": [
                {"issue_id": "T042-DIAG-002", "possible_causes_not_proven": "PRIVATE OCR DATA"},
                {"issue_id": "BAD-EXFIL", "conclusion": "PRIVATE OCR DATA"}
            ],
            "performance": {"max_ms": 810},
            "global_incidents": {"events": [
                {"token": "SECRET_API_TOKEN_EXAMPLE", "text": "PRIVATE OCR DATA"}
            ]},
            "spectator_examples": [{"candidate": ["Ah"], "operator_label": ["Td"]}],
        }

    def test_no_sensitive_data_leaks_to_issue(self):
        rendered = render(self.make())
        self.assertIn("T042-DIAG-002", rendered)
        self.assertIn("21", rendered)
        self.assertNotIn("BAD-EXFIL", rendered)
        self.assertNotIn("PRIVATE OCR DATA", rendered)
        self.assertNotIn("SECRET_API_TOKEN_EXAMPLE", rendered)
        self.assertNotIn("Ah", rendered)

    def test_not_called_accuracy_when_unlabeled(self):
        output = render(self.make())
        self.assertIn("NOT accuracy", output)
        self.assertIn("NOT_AVAILABLE", output)

    def test_baseline_gates_small_sample(self):
        self.assertIn("INSUFFICIENT_SAMPLES", compare(self.make(n=19), self.make(n=19))[0])
        a, b = self.make(n=100, unknown=30, stable=25), self.make(n=100, unknown=40, stable=20)
        result = compare(a,b)
        self.assertTrue(any("baseline 40.0%, current 30.0%" in x for x in result))
        self.assertTrue(any("fewer than 20 reviewed" in x for x in result))

    def test_zero_samples_do_not_divide(self):
        self.assertIsNone(normalized(self.make(n=0))["uncertain_rate"])

if __name__ == "__main__":
    unittest.main()
