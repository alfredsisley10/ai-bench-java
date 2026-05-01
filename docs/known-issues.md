# Known issues

Operating notes about behaviors the harness exhibits that aren't bugs in
the harness itself but are worth knowing when interpreting results.

## LLMs auto-correct broken code while quoting it

**Symptom.** A benchmark run lands in `FAILED_PATCH_APPLY` with the
audit-page message "git apply rejected the LLM's patch: error: patch
failed: <file>:<line> ... patch does not apply". The LLM's diff looks
well-formed (correct multi-module path, numeric hunk header, real `-`
vs `+` difference) but the `-` lines don't match what's actually in the
file at that anchor.

**Cause.** When the prompt includes the buggy source code, some models
(observed: copilot-gpt-4-1) tend to **rewrite the broken line as if it
were already fixed** when transcribing it into the diff's `-` line.
The patch then references content that doesn't exist in the file.

Two concrete cases observed in the rebuilt corpus:

* **BUG-0006** (`LoanStatus.canTransitionTo`): break-branch source has
  `case APPROVED -> next == FUNDED || next == ACTIVE || next == DECLINED;`
  (the extra `|| next == ACTIVE` is the bug — APPROVED shouldn't allow
  a direct skip to ACTIVE). The LLM's patch dropped `|| next == ACTIVE`
  from BOTH the `-` and `+` line, then "fixed" nothing. git apply
  rejected because the file doesn't contain the abbreviated form.
* **BUG-0001** (`AchCutoffPolicy.isBeforeFinalSameDayCutoff`):
  break-branch source has `return !et.toLocalTime().isAfter(...)` (the
  `!isAfter` is the boundary-inclusivity bug). The LLM's patch wrote
  `return et.toLocalTime().isBefore(...)` on both sides — wishful-quoting
  the source as already-fixed.

**Why this is hard to fix in the harness.** No-op detection
(implemented in `RealBenchmarkExecutor`) catches *byte-identical* `-`/`+`
pairs after a successful apply. This case fails *before* the apply, so
the no-op check never runs. From git apply's perspective the patch is
indistinguishable from any other "wrong context" patch.

**Mitigations to try, in order of estimated effort:**

1. Stronger system-prompt language: explicitly state that the source
   shown is in its **broken** state and the model must quote `-` lines
   literally from that broken state, not from what the code "should"
   look like.
2. Pre-flight ask: prompt the model to first identify the buggy line
   verbatim (one chat turn) before producing a diff (second turn).
   Catches auto-correction at the verbatim-quote step.
3. Post-rejection retry: when `git apply` rejects, feed the rejection
   back to the model with the actual file content excerpt at the
   anchor and ask for a corrected diff.

None of these are scoped here — record-and-park.

**Operator workaround.** Use `oracle` or `appmap-navie` context
providers. They expose the same broken source but the issue is
intrinsic to the LLM's transcription behavior; recurrence is reduced
(though not eliminated) when the source file is the only file in the
prompt and the model has nowhere to look but at it.

## BM25 retrieval can miss the bug's source file

**Symptom.** With `contextProvider = bm25`, the audit shows the source
file the bug touches isn't in the BM25-ranked top 5; instead, the
file's *test* file (or unrelated files mentioning similar terminology)
ranks higher. LLM patches against a remembered/imagined file shape.

**Cause.** Bug problem statements use behavioral / domain language
("rounding", "precision", "cutoff time"), which often appears with
higher density in test files (which describe expected behavior in
prose) than in concise production source.

**Mitigation (implemented).** The `bm25` provider is now hybrid: it
always includes `bug.filesTouched` files first (oracle-anchored), then
fills the remaining slots with BM25-ranked supplements. See
`ContextProvider.bm25()`.

**Why "pure BM25" is no longer offered.** It demonstrably fails to
include the file under edit when bug-statement keywords are
domain-behavioral rather than code-mechanical. Pure BM25 produced
zero incremental signal vs. the hybrid form, and the audit page's
rationale row makes the hybrid composition transparent. If a future
need arises to A/B "pure BM25 vs hybrid", revert to the pre-hybrid
form behind a new `bm25-pure` provider id.
