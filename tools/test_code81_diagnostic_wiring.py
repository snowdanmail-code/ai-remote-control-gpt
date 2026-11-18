import pathlib
import unittest

ROOT=pathlib.Path(__file__).resolve().parents[1]
JAVA=ROOT/'android/app/src/main/java/com/example/airemote'

class Code81DiagnosticsTests(unittest.TestCase):
    def test_card_failure_breakdown_is_exported(self):
        s=(JAVA/'PlayerVisionJournal.kt').read_text()
        for k in ['visual_failure_breakdown','hole_region_missing',
                  'hole_rank_or_suit_unreadable','last_source_dimensions',
                  'ocr_geometry_hints_not_authority']:
            self.assertIn(k,s)
    def test_exact_frame_provenance_untouched(self):
        o=(JAVA/'OnDeviceAutomaticOcr.kt').read_text()
        self.assertIn('OcrFrameProvenance.check(ticket, FrameStore.latest',o)
        self.assertIn('PlayerOcrIncidentPolicy.conflictWorthLogging(',o)
    def test_dimensions_from_original_screen(self):
        self.assertIn('screen.width, screen.height)',
                      (JAVA/'CaptureService.kt').read_text())
    def test_export_flags_are_snapshot_only(self):
        s=(JAVA/'PlayerEngineeringReport.kt').read_text()
        self.assertIn('capture_checks_are_export_time_snapshot',s)
    def test_no_actions_or_cloud_from_diagnostics(self):
        s=(JAVA/'PlayerVisualIssueDiagnosis.kt').read_text()
        self.assertNotIn('dispatchGesture(',s)
        self.assertNotIn('requestVerifiedAction(',s)
        self.assertNotIn('api.openai.com',s)

if __name__=='__main__':
    unittest.main()
