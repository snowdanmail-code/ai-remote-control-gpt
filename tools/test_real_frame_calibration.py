import json
import tempfile
import unittest
from pathlib import Path
from PIL import Image
from prepare_real_frame_calibration import make_profile


class RealFrameCalibrationToolsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name)
        self.image = root / 'fixture.png'
        Image.new('RGB', (1080, 2340)).save(self.image)
        self.region_file = root / 'regions.json'
        self.zones = [
            {'role': 'SEAT_ACTION', 'seatIndex': 0, 'boundsPixels': [100, 200, 380, 360]},
            {'role': 'SEAT_ACTION', 'seatIndex': 1, 'boundsPixels': [650, 200, 900, 360]},
            {'role': 'POT', 'boundsPixels': [400, 900, 680, 1040]}
        ]
        self.save()

    def save(self):
        self.region_file.write_text(json.dumps({'zones': self.zones}))

    def make(self, **changes):
        args = dict(image_path=self.image, regions_file=self.region_file,
                    package='com.example.pokertest', layout_key='operator-measured')
        args.update(changes)
        return make_profile(**args)

    def test_operator_measured_only(self):
        p = self.make()
        self.assertEqual(p['schemaVersion'], 1)
        self.assertEqual(p['frameWidth'], 1080)
        self.assertEqual(p['zones'][0]['bounds'], [93, 85, 352, 154])

    def test_actual_capture_dimensions(self):
        p = self.make(capture_width=540, capture_height=1170)
        self.assertEqual((p['frameWidth'], p['frameHeight']), (540, 1170))

    def test_reject_wrong_aspect_ratio(self):
        with self.assertRaises(ValueError):
            self.make(capture_width=960, capture_height=960)

    def test_missing_identity_is_not_guessed(self):
        with self.assertRaises(ValueError):
            self.make(package='1win')

    def test_reject_overlapping_players(self):
        self.zones[1]['boundsPixels'] = [200, 250, 650, 380]
        self.save()
        with self.assertRaises(ValueError):
            self.make()

    def test_reject_outside_image(self):
        self.zones[1]['boundsPixels'] = [900, 200, 1110, 380]
        self.save()
        with self.assertRaises(ValueError):
            self.make()

    def test_reject_duplicate_seat_identity(self):
        self.zones[1]['seatIndex'] = 0
        self.save()
        with self.assertRaises(ValueError):
            self.make()

    def test_reject_bad_global_seat(self):
        self.zones[2]['seatIndex'] = 2
        self.save()
        with self.assertRaises(ValueError):
            self.make()

    def test_reject_subpixel_boxes(self):
        self.zones[2]['boundsPixels'] = [400, 900, 400, 1000]
        self.save()
        with self.assertRaises(ValueError):
            self.make()

    def test_source_wiring_and_no_fake_identity(self):
        root = Path(__file__).parents[1]
        android = root / 'android/app/src/main/java/com/example/airemote'
        code = (android / 'OnDeviceAutomaticOcr.kt').read_text()
        activity = (android / 'MainActivity.kt').read_text()
        self.assertIn('OnDeviceRealFrameRegions.inspect(', code)
        self.assertIn('OnDeviceLayoutImport.parse(bytes)', activity)
        self.assertIn('OperatorStopStore.stop(this)', activity)
        self.assertNotIn('ConfirmedOpponentAction(', (android / 'RealFrameRegionDiagnostics.kt').read_text())
        self.assertIn('OnDeviceRealFrameRegions.clear()', (android / 'OperatorStopStore.kt').read_text())


if __name__ == '__main__':
    unittest.main()
