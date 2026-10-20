# План AI Remote Control GPT после v2.14.26

| Step | Component | Evidence / acceptance | State |
|---|---|---|---|
| 1 | Real-frame calibration import and OCR region routing | Explicit package, JPEG size, no assumed geometry, stale/STOP rejection | Source implemented; local JVM/Python tests pass |
| 2 | Obtain authentic redacted 1win frames | Operator-provided frames, independently annotated regions, actual app package and device scale | Blocked on real annotated captures |
| 3 | State delta and player binding | Confirm seat position, turn ownership, stacks and action changes from independent signals | Not connected to real frames |
| 4 | Hand/table identity | Independent verified source + two-frame temporal checks | Not supplied |
| 5 | Statistical learning loop | Only verified hand end triggers SQLite, preserve immutable hand history | Existing source, needs device validation |
| 6 | Strategy integration and math | Verified inputs, deadline gates and consent-based authorized mode | Math source tested; GPT connection missing |
| 7 | Safety and test reports | STOP, atomic publication, diagnostics, manual export | Local code/test evidence; Android run outstanding |
| 8 | Build and real Android validation | Debug APK, installs, permission flow, logged device test | Deferred per operator; NOT BUILT |

All real-game automation requires confirmation that this mode of use is permitted by the platform and involves no withdrawable value, money or prizes.
