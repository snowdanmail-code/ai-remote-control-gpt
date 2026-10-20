from pathlib import Path
import json
import unittest
from audit_recorded_surface import classify

class RecordedSurfaceTests(unittest.TestCase):
    def test_real_palette_synthetic_approximation(self):
        self.assertEqual(classify([(15,22,38)]*6)[0], 'PANEL_VISIBLE')
        self.assertEqual(classify([(95,97,103)]*6)[0], 'TABLE_LIKE')
        self.assertEqual(classify([(255,255,255)]*6)[0], 'UNKNOWN')
        self.assertEqual(classify([(12,22,37)]*2+[(243,243,243)]*4)[0], 'UNKNOWN')
    def test_labels_are_bounded_and_have_clear_expected_states(self):
        x=json.loads(Path(__file__).with_name('recorded_surface_labels.json').read_text())
        self.assertEqual(len(x['clips']),3)
        self.assertEqual(sum(len(c['samples']) for c in x['clips']),32)
        self.assertTrue(any(s['expected']=='BLOCK' for c in x['clips'] for s in c['samples']))
        self.assertTrue(all(s['expected'] in ('TABLE','BLOCK') and s['second']>=0 for c in x['clips'] for s in c['samples']))
    def test_source_wiring_blocks_before_cards_and_ocr(self):
        root=Path(__file__).parents[1]
        source=root/'android/app/src/main/java/com/example/airemote'
        capture=(source/'CaptureService.kt').read_text()
        assert 'RecordedSurfaceGate.inspect(width, height)' in capture
        assert capture.index('RecordedSurfaceGate.inspect(width, height)')<capture.index('OnDeviceCardReader.inspectAll(screen)')
        assert capture.index('RecordedSurfaceGate.inspect(width, height)')<capture.index('OnDeviceAutomaticOcr.offer(')
        assert 'FrameStore.invalidateOccludedSurface' in capture
        assert 'OnDeviceAutomaticOcr.reset()' in capture
        region=(source/'RealFrameRegionDiagnostics.kt').read_text()
        assert 'amountMinorUnits' in region
        assert 'parseMoneyMinorUnits(text)' in region
        assert 'ConfirmedOpponentAction(' not in region
        assert 'dispatchGesture(' not in (source/'RecordedSurfaceGate.kt').read_text()

if __name__=='__main__': unittest.main()
