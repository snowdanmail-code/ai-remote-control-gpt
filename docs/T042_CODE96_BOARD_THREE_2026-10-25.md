# Code96 screenshot rank three regression — 2026-10-25

Owner-provided image 709×1536: public board **3♥ В♦ 4♦ 5♠ 9♥**, personal **Т♠ 10♦**. Overlay showed **?♥ В♦ 4♦ 5♠ 9♥**.

Grounded cropped-card analysis at 540×1170 gives first card bounds x95–162, y546–638. Glyph-only 24×24 mask for 3 has minimum distance 0.0347 to an already verified 3, versus nearest other-rank (5) 0.1111. The original general separation requirement 0.075 is close to this observed margin (0.0764); slight variations in cropping may reject correct 3. This is a candidate cause, not proof of internal phone classifier scores.

Code96 adds ONLY this user-verified glyph mask, and a strong-three conditional minimum distance <=0.06 / next-rank margin >=0.055 with at least three user-verified 3 examples. No change to suit recognition, OCR, automatic taps, authorization or STOP. Existing independent-reader disagreements remain rejected; safety stays fail-closed. Includes regressions for the real silhouette and all previous stored glyphs.

Tests on known masks do not prove recognition from new live captures. User should test real Samsung after build and supply fresh screenshots.
