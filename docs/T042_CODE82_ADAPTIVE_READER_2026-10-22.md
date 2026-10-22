# T042 Code82 engineering report and adaptive reader follow-up (22 October 2026)

The user-provided engineering JSON came from Code82. quality_kpis inspected=21, spectator-only complete_single_frame_candidates=1, two_frame_agreements=0, human_confirmed=0, uncertain=10, no_detected_board=10. This does NOT prove Code82 player-hole recognition at all, because the engineering exporter uses SpectatorLearningJournal and did not embed PlayerVisionJournal. The existing speculative spectator examples (3c, 9c and other rank/suit guesses) are not ground truth.

Root-cause audit:
- Primary hero detector CardVisionCore.detect searches fixed ref x=240..420, y=852..998 in a 540x1170 scaled image. Failure to find the bright joined pair means the entire primary read returns no hole or board.
- The export button writes SpectatorEngineeringReport; separate PlayerEngineeringReport must previously be exported by another action. Misleading one-file player diagnosis was caused by this partition.
- The JSON has neither screenshots nor original source dimensions; therefore exact pixel misalignment is still a hypothesis, not a verified root cause.

New alternative V2: AdaptivePlayerCardReader scans a wider bounded lower-table zone using connected near-white pair geometry. It then calls the unchanged strict rank/suit templates, rejects incomplete, inconsistent, duplicate or multiple candidates. OnDeviceCardReader.inspectAll keeps a COMPLETE primary result; where legacy is incomplete, an adaptive candidate enters the existing CardConsensus only if no legacy-known card conflicts. This is a different region-detection path, not an ML model or a confirmed successful reader. It does not touch spectator reader, capture origin, table/hand proof, operator STOP, permits or gesture controls.

Reporting: combined engineering export now embeds player_vision_read_only with source dimensions, the existing breakdown, OCR geometry hints and counts of adaptive candidate frames. Existing spectator quality_kpis stays separate. No private card strings/screenshots/OAuth data in logs.

Criteria for success: collect Code83 player_vision_read_only counters and separately ground-truth redacted screenshots to measure exact errors. Do NOT treat simulated JVM PASS as proof of player recognition in the actual 1win application. If V2 returns 0 complete hands, next step is screenshot calibration/training; no guesses and no blind gestures.
