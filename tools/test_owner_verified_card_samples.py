import pathlib
import re
import unittest
ROOT=pathlib.Path(__file__).resolve().parents[1]
SRC=ROOT/"android/app/src/main/java/com/example/airemote"

class OwnerVerifiedCardRankFixtures(unittest.TestCase):
    def test_real_user_rank_masks_persist_in_runtime_code(self):
        f=(SRC/"VerifiedCardRankSamples.kt").read_text()
        entries=re.findall(r"^\s*[2-9TJQKA]\|[0-9a-f]{144}$",f,re.M)
        self.assertEqual(len(entries),23)
        self.assertIn("Crop ONLY rank glyph",f)
        self.assertIn("card.height*305/1000",f)
        self.assertIn("val maximumDistance = if (examplesOfRank == 1) 0.045 else 0.11",f)
        self.assertIn("runnerUp-first.value>=minimumSeparation",f)
    def test_strictly_fail_on_disagreeing_readers(self):
        src=(SRC/"CardVisionCore.kt").read_text()
        self.assertIn("VerifiedCardRankSamples.classify(r, card)",src)
        self.assertIn("legacy.rank != verified.rank -> null",src)
    def test_short_glyph_overlay_without_suit_words(self):
        code=(SRC/"VisibleCardOverlayText.kt").read_text()
        for word in ("ЧЕРВИ","ПИКИ","БУБНЫ","КРЕСТИ","ТУЗ","КОРОЛЬ","ДАМА","ВАЛЕТ"):
            self.assertNotIn('"'+word+'"',code)
        for short in ('\'A\' to "Т"', '\'Q\' to "Д"', '\'J\' to "В"', '\'h\' to "♥"'):
            self.assertIn(short,code)

if __name__=="__main__":
    unittest.main()
