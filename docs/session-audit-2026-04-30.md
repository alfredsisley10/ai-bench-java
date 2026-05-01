# Session audit — 2026-04-30

What landed in the long iteration that started from "rerun T4, the
bridge is live now" and ran through the FAILED_PATCH_APPLY
investigation, the BM25 + auto-correction findings, the leaderboard
formatting fix, and the Layer-C work for AppMap-Navie + real traces.
Linked from `docs/eval-protocol.md`'s ablation arms.

## Shipped

All commits live on `origin/main` (CI bot interleaves `dist/` rebuild
commits, omitted below).

| Topic | Commit | What it does |
|---|---|---|
| AppMap viewer in audit | `70fc38f` | Audit page deep-links cache traces to the embedded `/demo/appmap/view`. |
| Multi-thread executor | `74491f1` | Restored cached thread pool; LLM bridge call serialized via `ReentrantLock(true)`; watchdog made queue-aware. |
| Leaderboard ctx grouping | `3492399` | Each row keyed by (provider, modelId, contextProvider, appmapMode). |
| Sortable-table filter fix | `0bbe797` | Filters now read `data-filter-col`, not array index — was off-by-one. |
| Patch-apply diagnostics | `93743b0` | Prompt rules (multi-module path, numeric `@@`, no no-ops) + no-op detector + outcome banner on audit page. |
| Copilot bridge VSIX 0.2.19 | `27c628b` | Pending/failed activity tracking + full prompt viewer + JSON API. |
| BM25 hybrid + known issues | `3271395` | BM25 anchors `bug.filesTouched` + `docs/known-issues.md` documenting the LLM auto-correction pattern. |
| Leaderboard width fix | `a35db87` | Bug-id moved to tooltip; difficulty/category cells wrap. |
| Layer-C Navie | `f9f9c44` | `NavieCacheManager` + `/admin/navie` precompute page; `appmap-navie` ctx reads cache, falls back to Oracle on miss. |
| Layer-C real AppMap traces | `b552f2a` | `AppMapTraceManager` prefers `banking-app/<module>/tmp/appmap/junit/` recordings; `/admin/appmap-traces` per-module generate page. |

## Verified in-session

- 12-run cross-product pre-fix: 4/12 `FAILED_PATCH_APPLY`, 3/12
  `FAILED_TESTS`, 5/12 `PASSED`. Failure root causes reproduced
  locally with `git apply` (corrupt `@@ ... @@`, hallucinated paths,
  no-op `-`/`+` pairs).
- Post-fix verification batch (3 bugs × `bm25`): patches now produce
  well-formed unified diffs (correct multi-module paths, numeric hunk
  headers, real `-`/`+` differences). The remaining failures shifted
  to a new class of issue documented in `docs/known-issues.md`.
- `appmap navie` CLI smoke (BUG-0011 question, 13-min timeout):
  trajectory grew to 254 events, identified the bug's actual source
  file (`Percent.java`) plus 9 semantically-related files. Layer-C
  cache architecture works end-to-end with the local Copilot bridge.
- `./gradlew :compliance:test -Pappmap_enabled=true --no-configuration-cache`
  smoke: produced 23 real `.appmap.json` files in 57s.

## Known issues recorded for follow-up

`docs/known-issues.md` captures both:

1. **LLM auto-correction**: copilot-gpt-4-1 sometimes rewrites a
   broken line as if it were already fixed when transcribing it into
   the diff's `-` line — `git apply` then rejects because the file
   doesn't actually contain that text. Three mitigation paths sketched
   (stronger prompt language; pre-flight identify-buggy-line turn;
   post-rejection retry with file excerpt). None implemented.
2. **BM25 retrieval gap (closed by `3271395`)**: bug-statement
   keywords sometimes match test files better than source; pure BM25
   missed `Percent.java` for BUG-0011. Hybrid mode now anchors
   `bug.filesTouched` so the file under edit is always present.

## Operator next steps to actually use the new infrastructure

These are deliberately walk-away tasks the operator runs once after
landing this work:

1. **Generate real AppMap traces** for the hand-written modules:
   `/admin/appmap-traces` → "Generate traces for all uncovered
   modules". Single-thread queue; ~30s–5min per module across 14
   modules ≈ 30-60min total. No bridge calls.
2. **Precompute Navie context** for all 12 bugs: `/admin/navie` →
   "Precompute all uncached bugs". Single-thread queue against the
   bridge mutex; ~15-30min per bug × 12 ≈ several hours. Once cached,
   `appmap-navie` benchmarks return Navie's file selection instantly
   (HIT in audit rationale) instead of the Oracle fallback.
3. **Re-run the full permutation matrix** (P4 in the task list). With
   real traces in place, BM25 hybrid landed, and Navie precomputed,
   the apples-to-apples comparison across context providers is now
   meaningful in a way the pre-fix data wasn't.
