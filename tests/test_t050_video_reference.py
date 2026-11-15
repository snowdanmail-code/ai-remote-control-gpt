import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
REFERENCE = ROOT / "evidence" / "T050_video_reference_labels.json"


class VideoSeatReferenceTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.ref = json.loads(REFERENCE.read_text(encoding="utf-8"))

    def test_metadata_and_roi_coordinate_bounds(self):
        d = self.ref
        self.assertEqual("t050.recorded-seats.v1", d["schema"])
        self.assertEqual((1080, 2340), (
            d["video1"]["width"], d["video1"]["height"]))
        self.assertEqual(3, len(d["pixel_rois"]))
        for item in d["pixel_rois"]:
            x1, y1, x2, y2 = item["rect_px"]
            self.assertTrue(0 <= x1 < x2 <= 1080)
            self.assertTrue(0 <= y1 < y2 <= 2340)
            for numbers in item["reference"].values():
                for number in numbers.values():
                    self.assertGreaterEqual(number, 0)
                    self.assertLessEqual(number, 1)

    def test_true_replay_color_difference_but_not_action_identity(self):
        d = {x["name"]: x for x in self.ref["pixel_rois"]}
        self.assertGreater(
            d["left_action"]["reference"]["t42"]["green"] -
            d["left_action"]["reference"]["t40"]["green"], 0.50)
        self.assertGreater(
            d["top_action"]["reference"]["t42"]["light"] -
            d["top_action"]["reference"]["t40"]["light"], 0.50)
        self.assertGreater(
            d["right_action"]["reference"]["t48"]["light"] -
            d["right_action"]["reference"]["t40"]["light"], 0.30)
        self.assertIn("missing_cards_is_not_player_identity", self.ref["invariants"])

    def test_bank_already_includes_per_seat_wagers(self):
        second = self.ref["video2"]["events"][0]
        self.assertEqual(second["pot_minor"],
                         sum(second["contributions_minor"].values()))
        self.assertIn("pot_is_not_added_to_seat_wagers",
                      self.ref["invariants"])

    def test_two_simultaneous_action_badges_are_different_seats(self):
        observed = self.ref["video1"]["events"][1]["actions"]
        self.assertEqual({"top", "upper_left"},
                         {x["seat"] for x in observed})
        self.assertEqual({"Рейз", "Фолд"},
                         {x["text"] for x in observed})


if __name__ == "__main__":
    unittest.main()
