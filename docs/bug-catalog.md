# Bug catalog format

Each bug is a single file: `bugs/BUG-NNNN.yaml`. The file is metadata — the actual buggy code and fix live in the `banking-app` git history as two commits tagged `bug/BUG-NNNN/break` and `bug/BUG-NNNN/fix`.

## Schema

```yaml
id: BUG-0042
title: "ACH same-day cutoff off-by-one at end of business day"
module: payments-hub
difficulty: medium             # trivial | easy | medium | hard | cross-cutting
category: timing               # logic | timing | concurrency | numeric | security | data | state-machine | n-plus-one
tags: [ach, cutoff, timezone]

break_commit: bug/BUG-0042/break
fix_commit:   bug/BUG-0042/fix

files_touched:
  - payments-hub/src/main/java/com/omnibank/payments/ach/AchCutoffPolicy.java
oracle_diff_lines: 4           # patch minimality baseline

hidden_test:
  class: com.omnibank.payments.ach.AchCutoffPolicyTest
  method: rejects_submission_one_second_after_cutoff
  file: payments-hub/src/test/java/com/omnibank/payments/ach/AchCutoffPolicyTest.java

problem_statement: |
  Customers report that ACH submissions made exactly at the published
  5:00 PM ET cutoff are sometimes accepted into the same-day window and
  sometimes rejected. Stabilize the cutoff semantics: submissions at or
  after 17:00:00.000 America/New_York must be rejected.

hints:                          # Optional — used only for ablation studies
  - "Look at how AchCutoffPolicy compares Instant to ZonedDateTime"

appmap:
  recommended_tests:            # Tests whose AppMap traces give load-bearing context
    - com.omnibank.payments.ach.AchSubmissionServiceTest
  trace_focus:
    - "Execution path through AchCutoffPolicy.isBeforeCutoff"
```

## Difficulty tiers

- **trivial** — single-expression fix, obvious from a quick read. Baseline control.
- **easy** — single-method fix, low cross-file coupling. SWE-Bench "easy" analog.
- **medium** — single-module, may require understanding 2–4 files' interaction.
- **hard** — requires understanding behavior across 2+ modules.
- **cross-cutting** — requires runtime understanding (races, N+1, transaction boundary, state-machine edges). AppMap traces should provide asymmetric advantage here — this tier is the study's core hypothesis.

## Bug categories

Chosen so that AppMap traces provide more or less uplift depending on category, letting us characterize *when* runtime context helps:

| Category | AppMap uplift hypothesis |
|---|---|
| `logic` | low — static reading usually suffices |
| `timing` / `concurrency` | high — interleavings visible in traces |
| `numeric` | low–medium |
| `security` | medium |
| `data` (migrations, constraints) | medium |
| `state-machine` | high — actual transitions visible |
| `n-plus-one` | very high — SQL log obvious in trace |

## Scoring

Each bug contributes one row per (solver × appmap_mode × seed) combination:

```
bug_id, solver, appmap_mode, seed, passed, prompt_tokens, completion_tokens,
wall_ms, patch_lines, oracle_lines, cyclomatic_delta, regressions_introduced
```

Aggregate views in the webui pivot by (category × difficulty × appmap_mode).
