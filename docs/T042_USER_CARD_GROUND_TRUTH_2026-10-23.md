# Ground-truth card reading examples (owner screenshots, 2026-10-23)

Only card symbols and their positions are stored here. No usernames, bets, account ID, balances or full screenshots. The 29 annotated card crops are also in the separate operator audit archive; their rank glyphs are encoded as 23 distinct masked examples in VerifiedCardRankSamples.kt. All values were manually read by visually matching the exposed cards, not by trusting the app overlay. Identical community cards on flop and river are two separate photographed observations.

Captured time,Observation kind,Ground-truth cards,App overlay finding
02:30:22,Hero,K♦ 7♣,7♣ sometimes shown; K♦ missed
02:31:10,Hero,8♥ В♣,В♣ shown; 8♥ missed
02:33:31,Hero,6♦ 9♥,both unrecognized
02:33:31,Board,Д♣ 10♥ Т♣,three correct on snapshot
02:34:02,Hero,6♦ 9♥,both unrecognized
02:34:02,Board,Д♣ 10♥ Т♣ 9♣ 5♠,9♣ unrecognized
02:34:54,Hero,В♠ К♦,В♠ shown; К♦ missed
02:35:05,Hero,Д♦ Т♥,both unrecognized
02:35:52,Hero,В♠ Д♠,В♠ shown; Д♠ missed
02:37:41,Hero,9♥ 8♣,8♣ shown; 9♥ missed
02:38:00,Hero,4♦ 3♦,both unrecognized
02:38:25,Board,8♥ К♥ 10♥,К♥ shown; 8♥ and 10♥ missed

An empty board before flop is normal; screenshots without visible hero cards are not hero recognition failures. This manifest is training/validation material only, not a real-time game state feed.
