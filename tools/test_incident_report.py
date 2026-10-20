import json
import unittest
from analyze_incident_report import analyze


class IncidentReportTest(unittest.TestCase):
    def setUp(self):
        self.doc = {'schema': 1, 'version': '2.14.21', 'events': [
            {'sequence': 1, 'elapsed_ms': 100, 'severity': 'INFO', 'code': 'SESSION_ARMED'},
            {'sequence': 2, 'elapsed_ms': 110, 'severity': 'INFO', 'code': 'COMMAND_REQUESTED', 'action': 'CALL', 'latency_ms': 300},
            {'sequence': 3, 'elapsed_ms': 300, 'severity': 'ERROR', 'code': 'COMMAND_UNCERTAIN', 'latency_ms': 900},
            {'sequence': 4, 'elapsed_ms': 500, 'severity': 'CRITICAL', 'code': 'OPERATOR_STOP'},
        ]}

    def test_incident_priority(self):
        report = analyze(self.doc)
        self.assertIn('COMMAND_UNCERTAIN', report)
        self.assertIn('Аварийные STOP', report)
        self.assertIn('Критический', report)
        self.assertIn('900 мс', report)

    def test_p95_nearest_rank_for_two_samples(self):
        self.assertIn('p95 **900 мс**', analyze(self.doc))

    def test_action_counts(self):
        report = analyze(self.doc)
        self.assertIn('Android: **1**', report)
        self.assertIn('результаты: **1**', report)

    def test_bad_schema(self):
        with self.assertRaises(ValueError):
            analyze({'schema': 2, 'events': []})

    def test_bad_events(self):
        with self.assertRaises(ValueError):
            analyze({'schema': 1, 'events': [{'code': 'password:secret'}]})

    def test_empty_does_not_claim_success(self):
        self.assertIn('не доказывает', analyze({'schema': 1, 'events': []}))

    def test_max_size(self):
        with self.assertRaises(ValueError):
            analyze({'schema': 1, 'events': [{'code': 'SESSION_ARMED'}] * 5001})

    def test_redaction(self):
        report = analyze(self.doc)
        self.assertNotIn('access_token', report)
        self.assertNotIn('1win account', report)


if __name__ == '__main__':
    unittest.main()
