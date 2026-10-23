# T042 Code92: rank 93% to 78% and corroboration pipeline

Owner requested: lower old numeric/glyph rank similarity threshold by **15 percentage points** (0.93 -> 0.78); preserve existing suit recognition unchanged; connect big-card glyph, two mini-card ranks at upper-left, and the preflop-only lower "Старшая рука" text; reject mixed-hand or postflop captions; test previous samples, then build an experimental APK.

This patch updates CardVisionCore.classifyRank threshold (only legacy rank similarity; difference to runner-up remains >=0.045 and verified-reader contradiction still fails closed). Threshold score is NOT a calibrated probability. No blind game action is enabled.

PreflopRankFusion provides READ-ONLY corroboration for visible overlays: a visually detected suit on a big card can combine with an independently read mini rank (when no conflicting known big-card rank); a fresh preflop OCR bottom pair, which sorts ranks high first, is treated as UNORDERED and may supply the missing rank only if the other visual rank is already known. If mini pair and OCR pair disagree, do not fill. If any confidently read big rank contradicts mini pair, reject mini and bottom as candidate fill. A missing suit remains unknown. Every inferred label has a "*" and PRELIMINARY warning. They **never** enter CardReading.hole, cardConsensus, strategy, bets or gestures.

Bottom hint is guarded by same capture epoch, fresh source and PreflopRankHintGate, which latches off after the first 3 board cards and only rearms upon two distinct new-hand mini observations; a temporarily missed flop never reenables it. Existing app and signing history retained, STOP unchanged.

Tests: PreflopRankFusionScenarios from visually grounded screenshot cases 2♠/6♦ and К♦/7♥, sorted OCR, conflicting hints, postflop, missing suit, and forbidden standalone digits 1/0. These synthetic cases validate the rules, not recognition accuracy on a phone; live Samsung screenshots remain required to validate field performance.
