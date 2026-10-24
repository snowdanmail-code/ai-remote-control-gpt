# T042 — 6, 8, 9 adjacent-card glyph validation

Evidence from the operator's 709x1536 game screenshots: the two side-by-side hole cards 6♦ / 9♥ were not both reliably recognized, and newer 2♦ / 8♦ also showed a missing rank while its suit was correctly recognized. The preceding Code94 addressed 9 only. This change adds a VISUAL-ONLY independent digit shape reader for 6, 8 and 9. It inspects small enclosed holes inside the already identified rank corner, with strict area/vertical-position limits. The 8 has two substantial cavities, a 6 one large lower cavity, a 9 one large upper cavity. A 10 (a one-plus-zero), Q, A, 4 and all other labeled ranks must never be silently converted to 6/8/9.

Three separate rank masks extracted from the operator's cropped screenshots (only digit bitmaps, no raw screenshots or personal data) are in RoundDigitTopologyScenarios. Every previously labeled mask is also checked: a non-matching topology result is a test failure. The detector reads three bounded 2-pixel card alignment shifts, requiring >=2 agreement and no contradictory accepted predictions.

IMPORTANT: A topology label is provisional, e.g. 6 ♦*, 8 ♦* or 9 ♥* in the operator overlay only. It cannot enter complete CardReading code, verified hand consensus, player strategy or any automatic gesture. If it contradicts an already template-confirmed 6/8/9, it vetoes that full code rather than choosing either. The existing suits, signing setup and emergency STOP are unchanged.

This does NOT certify live Samsung recognition accuracy; it tests screenshot-derived digit masks and conservative fallback logic.
