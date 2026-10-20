"""Checks that action-strip OCR is diagnostic-only and wired into the real capture path."""
import json
import tempfile
import unittest
from pathlib import Path
from PIL import Image
from prepare_real_frame_calibration import make_profile

ROOT = Path(__file__).parents[1]
SRC = ROOT / "android/app/src/main/java/com/example/airemote"


class ActionControlIntegrationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.image = self.root / "real_sample.png"
        Image.new("RGB", (1080, 2340)).save(self.image)
        self.zones = [
            {"role": "SEAT_ACTION", "seatIndex": 0, "boundsPixels": [60, 230, 300, 350]},
            {"role": "SEAT_ACTION", "seatIndex": 1, "boundsPixels": [710, 230, 970, 350]},
            {"role": "HERO_FOLD", "boundsPixels": [38, 2160, 325, 2280]},
            {"role": "HERO_CHECK_CALL", "boundsPixels": [380, 2160, 670, 2280]},
            {"role": "HERO_BET_RAISE", "boundsPixels": [760, 2160, 1030, 2280]},
        ]
        self.measurements = self.root / "measured.json"
        self.write()

    def write(self):
        self.measurements.write_text(json.dumps({"zones": self.zones}), encoding="utf-8")

    def build(self):
        return make_profile(self.image, self.measurements, "com.example.pokertest", "manual-measurement")

    def test_three_optional_hero_regions(self):
        p = self.build()
        self.assertEqual(len(p["zones"]), 5)
        self.assertEqual({z["role"] for z in p["zones"] if z["role"].startswith("HERO_")},
            {"HERO_FOLD", "HERO_CHECK_CALL", "HERO_BET_RAISE"})

    def test_partial_control_layout_rejected(self):
        self.zones.pop()
        self.write()
        with self.assertRaises(ValueError): self.build()

    def test_overlapping_control_layout_rejected(self):
        self.zones[-1]["boundsPixels"] = [650, 2160, 1000, 2280]
        self.write()
        with self.assertRaises(ValueError): self.build()

    def test_wrong_lane_rejected(self):
        self.zones[2]["boundsPixels"] = [650, 2160, 950, 2280]
        self.write()
        with self.assertRaises(ValueError): self.build()

    def test_bad_vertical_band_rejected(self):
        self.zones[2]["boundsPixels"] = [38, 1600, 325, 1700]
        self.write()
        with self.assertRaises(ValueError): self.build()

    def test_readonly_source(self):
        s = (SRC / "ActionControlDiagnostics.kt").read_text()
        self.assertIn("val authorizesTap: Boolean get() = false", s)
        self.assertIn("val confirmsHandOrTurn: Boolean get() = false", s)
        self.assertNotIn("dispatchGesture(", s)
        self.assertNotIn("VerifiedTapArea(", s)
        self.assertNotIn("AutonomousObservation(", s)

    def test_capture_and_stop_wiring(self):
        ocr = (SRC / "OnDeviceAutomaticOcr.kt").read_text()
        store = (SRC / "FrameStore.kt").read_text()
        capture = (SRC / "CaptureService.kt").read_text()
        regions = (SRC / "RealFrameRegionDiagnostics.kt").read_text()
        self.assertIn("OnDeviceActionStripDiagnostics.inspect(", ocr)
        self.assertIn("FrameStore.lastActionStrip = null", ocr)
        self.assertIn("lastActionStrip = null", store)
        self.assertIn("FrameStore.lastActionStrip = null", capture)
        self.assertIn("OnDeviceActionStripDiagnostics.clear()", regions)
        self.assertIn("OnDeviceActionStripDiagnostics.resetSession()", regions)

    def test_ui_and_versioning(self):
        ui = (SRC / "MainActivity.kt").read_text()
        self.assertIn("ПРОВЕРКА РЕАЛЬНЫХ КНОПОК", ui)
        self.assertRegex(ui, r"v2\.14\.(?:3[1-9]|[4-9][0-9])")


if __name__ == "__main__": unittest.main()
