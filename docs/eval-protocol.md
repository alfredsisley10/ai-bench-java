# Evaluation protocol

## Bespoke-bug run (banking-app)

1. **Worktree setup.** Create fresh worktree at `$TMP/ai-bench/runs/<run-id>` from `banking-app` at commit `bug/BUG-NNNN/break`.
2. **Baseline build.** `./gradlew build -x test` must succeed — any failure means the break commit itself is broken and the run aborts (bug is invalid).
3. **Baseline tests.** Run full test suite (excluding hidden test); record pass count. Any failing test other than the hidden test is unexpected — flag the run.
4. **AppMap trace collection (if armed).** Run the test suite again with the AppMap Java agent enabled (`-javaagent:appmap.jar`) against tests listed in `bug.appmap.recommended_tests`. Collect `tmp/appmap/*.appmap.json`.
5. **Prompt assembly.** Concatenate:
   - System prompt (fixed boilerplate: "you are fixing a bug in a Java Spring Boot codebase...")
   - Problem statement (from `bug.problem_statement`)
   - File list from `bug.files_touched` + their full current contents
   - Optionally: top-K relevant files by heuristic (not used in scored runs — ablation only)
   - Optionally (AppMap arm): trace files as attachments, with a prelude instructing the solver they are runtime captures
6. **Solver invocation.** Send assembled prompt through the chosen LLM adapter. Request a patch in unified-diff format.
7. **Patch application.** `git apply` the returned patch. Failures to apply count as a failed run.
8. **Hidden test execution.** Run only `bug.hidden_test.method`. Pass = green; Fail = red.
9. **Regression suite.** Run full test suite; compare pass count to baseline. `regressions_introduced = baseline_pass - current_pass`.
10. **Complexity delta.** Run `pmd` / custom cyclomatic analyzer over `bug.files_touched` pre and post. Record `cyclomatic_delta`.
11. **Report row.** Write a scored row to `runs/<run-id>/result.jsonl` and aggregate into `runs/index.jsonl`.

## Enterprise-repo run

Identical shape, but:
- Worktree seeded from the enterprise repo at a user-chosen base commit (typically before the fix PR that resolved a JIRA ticket).
- Problem statement comes from JIRA ticket description (optionally enriched with linked issues).
- Hidden test = the test that landed with the fix PR (harness auto-detects it by diff against the PR head).
- Regression suite = whatever the repo has. No oracle_diff_lines available; patch-minimality metric is omitted.
- Build step uses `harness-builder` with enterprise proxy + Artifactory config.

## Ablation arms

The benchmark orchestrator supports varying these independently to isolate the AppMap contribution:

| Variable | Arms |
|---|---|
| Solver | `corp-openai`, `copilot`, (future: `claude-direct`) |
| AppMap | `off`, `on-recommended-tests`, `on-all-tests` |
| Context | `files-touched-only`, `files-touched + top-K`, `full-module` |
| Seed | 3 seeds per (bug × arm) for variance estimation |

Default scored matrix: 2 solvers × 2 AppMap modes × files-touched-only × 3 seeds = 12 runs per bug. For a 50-bug catalog that is 600 runs — cheap enough to re-run as adapters evolve.

## Reporting

- `reports/summary.csv` — one row per run (as schema above).
- `reports/agg_by_category.csv` — pass rate, mean tokens, mean complexity delta pivoted by (category × appmap_mode).
- Webui dashboard reads the same CSVs and renders interactive pivot views.
