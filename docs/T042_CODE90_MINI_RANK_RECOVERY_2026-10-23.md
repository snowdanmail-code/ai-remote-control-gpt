# Code90 follow-up: root cause of three-source rank recovery, 2026-10-23

From TWO new user screenshots 709x1536: hero 2♠6♦ (the overlay outputs 2♠?♦), hero К♦7♥ (overlay ?♦?♥). Both show the corresponding face-up top-left mini cards and preflop bottom text "Старшая рука: 6 2" / "Старшая рука: К 7". Board empty, so the bottom line is eligible in principle.

Verified root causes in current Code90:
1. MiniHeroRankReader crops a COMPLETE small card and passes it to the normal full-size reader. NormalizedRankMask scans only top 34% of card height, while on user images the mini number reaches ~48%: cropped glyphs routinely lose their lower stroke, so rank classification fails. New read path crops mini rank at ~4..48% and pastes into a correctly padded proxy. It still uses existing rank classification thresholds and cannot claim accuracy without a real Samsung field retest.
2. OnDeviceTextRecognition uses ML Kit LATIN only. PreflopBottomRankText required literal Cyrillic "Старшая рука" in the same OCR line. New read-only parser also accepts exactly TWO rank tokens in the bottom summary region when OCR drops the Cyrillic heading. It is inactive after board-flop latch and does not permit "1 0" as two ranks.
3. In Code90 the mini and bottom hints were NOT card authority and were not used to repair missing hole ranks. This patch shows a separate '*' provisional preview with mini rank combined with an independently read suit, explicitly not confirmed. No CardReading.hole value is replaced; no betting logic changed.

Self/fixture tests do not establish real-world recognition. The preflop gate closes for 3+ observed board slots and stays latched even if board detector later briefly fails, re-arms only for 2 successive different mini pairs. No screenshot bytes, identities, dollar amounts, account IDs retained in the runtime tests.
