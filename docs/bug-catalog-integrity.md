# Bug catalog integrity report — 2026-04-30

## Summary

Of the 12 bugs in `bugs/BUG-*.yaml`, only **2 are usable benchmarks today**:
**BUG-0003** and **BUG-0008**. The other 10 either have a break branch that
equals main (no bug at all) or have break and fix branches that contain
identical content for the file the catalog claims is the bug. The harness
will report PASS or FAIL on those, but neither outcome reflects model
ability — the model has no bug to fix.

This was discovered while debugging a separate issue ("T4-rerun: 12/12 runs
hit `FAILED_PATCH_APPLY`"). The patch-apply issue itself is fixed in commit
`a2da814`. This document covers only the corpus problem.

## Per-bug status

The "broken file" column shows lines changed between
`bug/BUG-NNNN/break` and `bug/BUG-NNNN/fix` on the file(s) listed in the
bug's `filesTouched`. **0 lines = the catalog says this file changes
between break and fix, but on disk it doesn't.**

| Bug | Difficulty | Module | break SHA | broken file diff (break→fix) | Status |
|---|---|---|---|---|---|
| BUG-0001 | MEDIUM | payments-hub | `b39552ee` (same as main) | n/a — break is main | inert |
| BUG-0002 | EASY | shared-domain | `a3cda474` | 0 lines | inert |
| **BUG-0003** | **HARD** | **ledger-core** | **`5b219ba3`** | **2 lines (mixed-currency check)** | **real** |
| BUG-0004 | MEDIUM | shared-domain | `75b7e56a` | 0 lines | inert |
| BUG-0005 | MEDIUM | accounts-consumer | `1cfefa64` | 0 lines | inert |
| BUG-0006 | EASY | lending-corporate | `e7e41ef6` | 0 lines | inert |
| BUG-0007 | MEDIUM | lending-corporate | `040bf030` | 0 lines | inert |
| **BUG-0008** | **HARD** | **payments-hub** | **`1d1c4873`** | **2 lines (RTP/FEDNOW/BOOK enum)** | **real** |
| BUG-0009 | MEDIUM | ledger-core | `00874930` | 0 lines (across 2 files) | inert |
| BUG-0010 | EASY | payments-hub | `f85f7c15` | 0 lines | inert |
| BUG-0011 | TRIVIAL | shared-domain | `82db31b3` | 0 lines | inert |
| BUG-0012 | TRIVIAL | accounts-consumer | `f9ac94a8` | 0 lines | inert |

`main` SHA at the time of this report: `b39552ee`.

## Why the harness still produces results today

For inert bugs the LLM still emits a patch (typically a cosmetic refactor
of unrelated code or a no-op edit) because the prompt is non-empty and the
model is told to respond with a diff. With the patch-apply fix from
`a2da814`, those patches now apply cleanly to the worktree, the hidden test
runs against a state where the bug never existed, and the test passes.
**A PASS on an inert bug is a corpus artifact, not a measurement of model
capability.**

## The 2 real bugs

### BUG-0003 — Trial balance invariant broken by uncommitted posting with mixed currency

```diff
--- a/ledger-core/src/main/java/com/omnibank/ledger/internal/PostingServiceImpl.java
+++ b/ledger-core/src/main/java/com/omnibank/ledger/internal/PostingServiceImpl.java
@@ -83,7 +83,7 @@ public class PostingServiceImpl implements PostingService {
         for (PostingLine l : entry.lines()) {
             currencies.add(l.amount().currency());
         }
-        if (currencies.size() > 2) {
+        if (currencies.size() > 1) {
             throw new PostingException(PostingException.Reason.MIXED_CURRENCIES, currencies.toString());
         }
```

Off-by-one on the mixed-currency rejection threshold.

### BUG-0008 — Payment idempotency key collision returns wrong payment id

```diff
--- a/payments-hub/src/main/java/com/omnibank/payments/internal/PaymentServiceImpl.java
+++ b/payments-hub/src/main/java/com/omnibank/payments/internal/PaymentServiceImpl.java
@@ -95,7 +95,7 @@ public class PaymentServiceImpl implements PaymentService {
                     throw new IllegalStateException("Wire submitted outside Fedwire customer window");
                 }
             }
-            case RTP, FEDNOW -> {
+            case RTP, FEDNOW, BOOK -> {
                 // 24/7 — no window enforcement
             }
         }
```

Missing `BOOK` rail in the 24/7 settlement-window switch — book transfers
were being subjected to Fedwire window enforcement they shouldn't be.

## Re-seeding the inert bugs

For each inert bug, the break branch needs to actually contain the bug
that `bugs/BUG-NNNN.yaml` describes. The recipe:

1. Read the bug's YAML for `problemStatement`, `filesTouched`,
   `hiddenTest.{class,method,file}`.
2. Check out `bug/BUG-NNNN/fix` (the canonical-fix branch).
3. Inverse-apply the intended bug to the file in `filesTouched`. For
   off-by-one bugs (`isBefore` ↔ `!isAfter`, `>` ↔ `>=`, etc.) the change
   is one or two lines.
4. Confirm the hidden test fails on this branch:
   `./gradlew :MODULE:test --tests CLASS.METHOD` from banking-app.
5. Force-update `bug/BUG-NNNN/break` to point at the new commit:
   `git push origin +bug/BUG-NNNN/break`.

Specifically for **BUG-0001** (whose break already equals main): pick the
intended bug — the YAML asks for `isBeforeFinalSameDayCutoff` to use
strict-less-than, so the buggy version should use inclusive `!isAfter` —
inject that into `AchCutoffPolicy.java`, commit on top of main, force the
break branch there.

## Reproduction

To re-verify these findings any time:

```bash
cd banking-app
for n in 01 02 03 04 05 06 07 08 09 10 11 12; do
  bug="BUG-00${n}"
  ft=$(python3 -c "import yaml; d=yaml.safe_load(open('../bugs/${bug}.yaml')); print(' '.join(d['filesTouched']))")
  diff_lines=$(git diff bug/${bug}/break..bug/${bug}/fix -- $ft 2>/dev/null | grep -cE '^[+-][^+-]')
  echo "${bug}  ${diff_lines}-line diff on filesTouched"
done
```

A bug with `0-line diff` is inert. Anything > 0 is real.
