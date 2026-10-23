import pathlib
import unittest
ROOT=pathlib.Path(__file__).resolve().parents[1]
P=ROOT/"android/app/src/main/java/com/example/airemote"

class CardGlyphChainWiring(unittest.TestCase):
    def test_visual_parts_join_in_one_place(self):
        s=(P/"CardGlyphChain.kt").read_text()
        self.assertIn("val rank = CardVisionCore.classifyRank",s)
        self.assertIn("val suit = CardVisionCore.classifySuit",s)
        self.assertIn("combine(rank, suit, inkRed(r, card))",s)
        self.assertIn("rawSuit in \"hd\"",s)
        self.assertIn("val code = if (r != null && s != null)",s)
    def test_all_readers_share_same_logic(self):
        for path in ("CardVisionCore.kt","CenteredTableBoardReader.kt",
                     "AdaptivePlayerCardReader.kt","SeparateHeroCardReader.kt"):
            s=(P/path).read_text()
            self.assertIn("CardGlyphChain.read(",s,path)
    def test_partial_parts_never_enter_poker_codes(self):
        s=(P/"CardVisionCore.kt").read_text()
        self.assertIn("val hole = heroGlyphs.map { it.code }",s)
        self.assertIn("val community = boardGlyphs.map { it.code }",s)
        view=(P/"VisibleCardOverlayText.kt").read_text()
        self.assertIn('glyphs.getOrNull(i)',view)
        self.assertIn('reading.holeGlyphs',view)
        self.assertIn('reading.communityGlyphs',view)
    def test_stop_and_consensus_unchanged(self):
        overlay=(P/"LiveGestureAccessibilityService.kt").read_text()
        self.assertIn("OperatorStopStore.isStopped",overlay)
        capture=(P/"CaptureService.kt").read_text()
        self.assertIn("FrameStore.cardConsensus.accept(",capture)
        self.assertIn("OperatorStopStore.isStopped(",capture)
if __name__=="__main__":unittest.main()
