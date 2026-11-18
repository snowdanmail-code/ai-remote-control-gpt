# T042 owner screen review and 2-sided training labels, 2026-10-23

Six owner-supplied 709x1536 JPEG screenshots were visually reviewed, comparing ACTUAL exposed board / hero cards to what the transparent Code87 overlay showed. Do not trust overlay as ground truth. Source pictures remain only in this conversation; the repository stores only cropped 24x24 symbol masks with NO player names, account IDs, faces, avatars, balances, button positions or raw screenshots.

CASE 1 (032952): board 9d Qh 8h 9c; overlay first 3 correct, 9c unknown. Hero 5d Kc; overlay 5d unknown, Kc correct.
CASE 2 (032831): board 3s 7s Ac 3c Qs; overlay first 4 correct, Qs unknown. Hero cards not exposed; 'looking for two' is accurate.
CASE 3 (032422): board empty preflop (accurate); hero Ks 3d, 3d unknown.
CASE 4 (032342): board Ks 9h Th, Th unknown; hero 6s 5h, 5h unknown.
CASE 5 (032330): preflop board empty (accurate); hero 6s 5h, 5h still unknown (SAME cards, not independent new hand).
CASE 6 (032300): board 9d 8d 7s 7c, all correct; hero Kh Qh, Qh unknown.

The old large-suit classifier often did not identify otherwise legible cards. A SECOND independent small suit classifier now matches an 8-connected binary component under the rank in the UPPER LEFT corner, rather than just the large center suit. 8 silhouette templates from 4 board and 4 hero examples: two sample templates each of hearts, diamonds, spades and clubs. Measured by offline scripts on the 26 visible annotated cards in these exact 6 screenshots: 26/26 suit classifications with distance <=0.075 and alternate-suit separation >=0.07. This is in-sample, NOT field-proven generalization, and not a probability estimate. The old large-suit classifier remains; a disagreement becomes UNKNOWN instead of a guessed card.

Five additional rank masks cover Qs, 5d, 3d, 5h, Qh missed in these samples. Th and 9c are already in the previous rank-mask database, so did not need duplicate rank entries (their missing suit was more plausible). Rank '5' retains strict special acceptance thresholds to prevent earlier regression of 6->5; all JVM historical fixtures must pass.

CRITICAL: these are visually verified read-only training labels, not guaranteed game/source/hand identity. They do not authorize any bet or tap. Card origin checks, 2-frame agreement, STOP, and gesture controls untouched. Confirm changes on real Samsung screenshots, including a held-out hand NOT in this batch, before claiming accuracy or promoting builds. 
