# Preflop-only rank hints, mini-card crosscheck, T042 (23 October 2026)

Owner confirmed small cards at upper-left repeat the player's own two cards. The line `Старшая рука` should ONLY be used before the first three community cards appear. After flop, this line describes the best whole-hand result, NOT just private-card ranks. Never reuse it during flop/turn/river. Re-enable only with a new recognized hand.

The independent MiniHeroRankReader checks the top-left two white mini portraits at measured normalized coordinates, resamples their rank glyphs and uses existing strict rank templates. It retains missing ranks as unknown. Its results are only displayed in the transparent PLAYER card overlay as MINI and are not considered verified cards, independent table identity or actionable input. It uses no other account details.

PreflopRankHintGate runs on actual PLAYER capture samples inside the same atomic STOP/epoch/safe-window publication section as the existing CardVisionSignal. Any observed 3-5 board rectangles closes the text-hint gate. Board disappearance alone, incomplete OCR, or an unknown board cannot reopen it. Only a different mini pair of two recognized ranks stable across TWO later board-free frames can re-enable it; inability to confirm a new round means hints remain OFF. Session epoch changes reset all state.

The OCR preflop text parser requires the exact 'Старшая рука:' (or 'High card:') label, two rank tokens, and a normalized bottom-of-screen geometry. A fast later FLOP disables older asynchronous OCR results via both frame-at-capture and current gate checks. Parser outputs never feed legal action/strategy. The bundled Latin-script ML Kit model may not actually read Cyrillic reliably; this is a guarded data path, not established field accuracy.

Test synthetic state cases: preflop → flop → board-detection lapse (must remain disabled) → two newly observed mini pairs (new hand) → enabled; OCR after flop rejected; ambiguous location/text rejected. NO apk/Android16/device test has yet run for this change.
