# Bug catalog rebuild plan — 2026-04-30

## Context

Discovery from a real-LLM matrix run (12 launches × 4 contexts × 3 AppMap modes
on BUG-0003 with `copilot-gpt-4-1`):

- **All 12 runs FAILED** even though gpt-4.1 produced a *literally canonical*
  patch for BUG-0003 with oracle/navie context (`> 2` → `> 1`, identical to
  the reference fix).
- Real failure mode: gradle reported `No tests found for given includes:
  [com.omnibank.ledger.internal.PostingServiceImplIT]`. The hidden test file
  doesn't exist on `main`, `bug/BUG-0003/break`, or `bug/BUG-0003/fix`.
- Audit across the catalog: **only BUG-0001 and BUG-0002 have their hidden
  test files committed anywhere in git history.** Bugs 0003 through 0012
  reference test classes that were never authored.
- Earlier integrity audit (`docs/bug-catalog-integrity.md`) already noted
  that 10/12 bugs have zero diff between break and fix on `filesTouched`.
  Combined: **at most 2 bugs are even close to working as benchmarks** today,
  and even those two have artifacts (BUG-0001 break==main; BUG-0002
  break==fix on touched file) that make them inert.

## Goal

Rebuild the corpus so every bug satisfies these acceptance criteria:

1. The bug's `filesTouched` source file differs between
   `bug/BUG-NNNN/break` and `bug/BUG-NNNN/fix` (or `main`) by a small,
   targeted change matching `problemStatement`.
2. A hidden test class lives at `hiddenTest.file` on `main` (committed once;
   never overlaid; never shipped to the LLM in the prompt).
3. The hidden test **fails** when run against the buggy source (overlaid
   from the break ref) and **passes** when run against the fixed source.
4. The hidden test runs cleanly via `:<module>:test --tests
   <hiddenTest.class>` — no `IT` suffix on the class name unless the bug
   genuinely needs an integration-test setup, since `:test` excludes IT
   patterns by default.

## Branch topology

For each bug:

```
main  ──┬─ <fixed code + hidden test, committed to main>
        │
        ├─ bug/BUG-NNNN/fix    (= main, or a tiny commit pointing at main's
        │                       fix; harness reads filesTouched from this
        │                       only for the audit's "Reference fix" panel)
        │
        └─ bug/BUG-NNNN/break  (single regression commit — flips the bug
                                back into filesTouched; hidden test is
                                inherited from main and FAILS here)
```

**Why hidden tests live on main (not just on break/fix):** the harness's
worktree strategy copies the live `banking-app` HEAD and only overlays
`filesTouched` from the break ref — it does NOT overlay the hidden test
file. So tests must already exist in the live tree. The earlier "test on
break/fix only" approach silently broke the harness with `No tests found`.

**Why the LLM can't see the test:** `filesTouched` is what the prompt
assembler ships under the oracle / appmap-navie context providers. The
hidden test is referenced via `hiddenTest.file` which is read by the
audit page (for display only) but is NEVER added to `filesTouched`.

## Per-bug rebuild specs

The intended bug, the canonical fix, the hidden-test method, and any
notes-to-self for the test design. All test classes are JUnit 5 (Jupiter)
with AssertJ assertions — same shape as the existing
`AchCutoffPolicyTest` and `MoneyTest` that already work.

### BUG-0001 — ACH same-day cutoff off-by-one (TIMING / payments-hub)

| Field | Value |
|---|---|
| filesTouched | `payments-hub/src/main/java/com/omnibank/payments/ach/AchCutoffPolicy.java` |
| Hidden test | `com.omnibank.payments.ach.AchCutoffPolicyTest.rejects_submission_exactly_at_final_cutoff` |
| Status | Test exists. Source on main is correct (`isBefore`). Need to flip break to use `!isAfter` so 16:45:00 is inclusive (wrong). |
| Fix diff | `return !et.toLocalTime().isAfter(CUTOFF)` → `return et.toLocalTime().isBefore(CUTOFF)` |

### BUG-0002 — Money division truncates (NUMERIC / shared-domain)

| Field | Value |
|---|---|
| filesTouched | `shared-domain/src/main/java/com/omnibank/shared/domain/Money.java` |
| Hidden test | `com.omnibank.shared.domain.MoneyTest.division_does_not_throw_on_non_terminating` |
| Bug | `dividedBy` uses `MathContext.UNLIMITED` (or `setScale(0, UNNECESSARY)`) — throws on `100/3`. |
| Fix | Use `divide(divisor, currency.minorUnits + 4, RoundingMode.HALF_EVEN)` and re-round to currency scale. |

### BUG-0003 — Mixed-currency journal slips through (STATE_MACHINE / ledger-core)

| Field | Value |
|---|---|
| filesTouched | `ledger-core/src/main/java/com/omnibank/ledger/internal/PostingServiceImpl.java` |
| Hidden test | rename **`PostingServiceImplIT` → `PostingServiceImplCurrencyCheckTest`** (drop the IT suffix so `:test` finds it; bug doesn't actually need a DB integration test — the validate() check is pure) |
| Bug | `if (currencies.size() > 2)` — off-by-one (should be `> 1`). |
| Fix | `if (currencies.size() > 1)` (matches what gpt-4.1 produced — already verified canonical). |

### BUG-0004 — 30/360 day count snaps Feb 28 (NUMERIC / shared-domain)

| Field | Value |
|---|---|
| filesTouched | `shared-domain/src/main/java/com/omnibank/shared/domain/DayCountConvention.java` |
| Hidden test | `DayCountConventionTest.thirty_360_does_not_treat_feb_28_as_end_of_month` |
| Bug | `THIRTY_360.yearFraction` snaps `endDay == 28 || endDay == 29` to 30 unconditionally. |
| Fix | Only snap day-31 → 30 per the convention; remove the Feb-28 special case. |

### BUG-0005 — Hold expiring today excluded (TIMING / accounts-consumer)

| Field | Value |
|---|---|
| filesTouched | `accounts-consumer/src/main/java/com/omnibank/accounts/consumer/internal/ConsumerAccountServiceImpl.java` |
| Hidden test | rename `BalanceViewIT` → **`BalanceHoldFilterTest`**; mock the repository so the test is a unit test, not an IT. |
| Bug | `hold.expiresAt.toLocalDate().isBefore(today)` excludes any hold whose date is today; should include same-day expiries unless `now.isAfter(hold.expiresAt)`. |
| Fix | Compare `Instant` not `LocalDate`. |

### BUG-0006 — APPROVED → ACTIVE skips FUNDED (STATE_MACHINE / lending-corporate)

| Field | Value |
|---|---|
| filesTouched | `lending-corporate/src/main/java/com/omnibank/lending/corporate/api/LoanStatus.java` |
| Hidden test | `LoanStatusTest.approved_cannot_go_directly_to_active` |
| Bug | `canTransitionTo(APPROVED, ACTIVE)` returns true. |
| Fix | Remove the entry from the allowed-transitions map. |

### BUG-0007 — Amortization final installment drift (NUMERIC / lending-corporate)

| Field | Value |
|---|---|
| filesTouched | `lending-corporate/src/main/java/com/omnibank/lending/corporate/api/AmortizationCalculator.java` |
| Hidden test | `AmortizationCalculatorTest.final_installment_zeros_balance_even_under_rounding_drift` |
| Bug | Final-installment `if (i == periods)` clause was deleted. |
| Fix | Re-add: on the last iteration, principal-component absorbs the remaining balance. |

### BUG-0008 — Idempotency key collision (CONCURRENCY / payments-hub)

| Field | Value |
|---|---|
| filesTouched | `payments-hub/src/main/java/com/omnibank/payments/internal/PaymentServiceImpl.java` |
| Hidden test | rename `PaymentServiceImplIT` → **`PaymentIdempotencyTest`**; use a `ConcurrentHashMap`-backed in-memory repository in the test so it's pure-unit. |
| Bug | The submit flow doesn't synchronize on the idempotency key; concurrent inserts violate the unique index. |
| Fix | Compute-if-absent on a `ConcurrentMap<String, Payment>` keyed by idempotency key, OR `synchronized` on the key, before findByIdempotencyKey + insert. |

### BUG-0009 — N+1 over journal lines (N_PLUS_ONE / ledger-core)

| Field | Value |
|---|---|
| filesTouched | `ledger-core/src/main/java/com/omnibank/ledger/internal/JournalEntryRepository.java`, `JournalEntryEntity.java` |
| Hidden test | rename `LedgerQueriesImplIT` → **`JournalHistoryQueryTest`**; instrument with a query-counter (Hibernate `Statistics` if present, or a custom `SqlCounter` interface) so the test is unit-testable. |
| Bug | Default lazy-load on `lines` collection. |
| Fix | Add `@Query("... JOIN FETCH e.lines ...")` on the repository method. |

### BUG-0010 — Wire cutoff ignores Fed holidays (LOGIC / payments-hub)

| Field | Value |
|---|---|
| filesTouched | `payments-hub/src/main/java/com/omnibank/payments/wire/WireCutoffPolicy.java` |
| Hidden test | `WireCutoffPolicyTest.wire_cutoff_closed_on_fed_holiday` |
| Bug | `isFedwireOpen` checks weekday only. |
| Fix | Add a `FedHolidays.isObserved(date)` check (test injects a mock holiday list). |

### BUG-0011 — Percent.of leaves wrong scale (NUMERIC / shared-domain)

| Field | Value |
|---|---|
| filesTouched | `shared-domain/src/main/java/com/omnibank/shared/domain/Percent.java` |
| Hidden test | `PercentTest.applying_percent_preserves_currency_scale` |
| Bug | An extra `.setScale(10)` call slips into the multiplier. |
| Fix | Drop the `setScale(10)`. |

### BUG-0012 — Hold expiry inclusive boundary (LOGIC / accounts-consumer)

| Field | Value |
|---|---|
| filesTouched | `accounts-consumer/src/main/java/com/omnibank/accounts/consumer/internal/HoldEntity.java` |
| Hidden test | `HoldEntityTest.hold_is_active_at_exact_expiry_boundary` |
| Bug | `now.isBefore(expiresAt)` — exclusive on expiresAt. |
| Fix | `!now.isAfter(expiresAt)` — inclusive. |

## Naming change: drop the `IT` suffix from 4 hidden tests

Bugs 0003, 0005, 0008, 0009 originally targeted classes ending in `IT`
(integration test). Banking-app's `:<module>:test` task uses the default
JUnit 5 launcher pattern which excludes `*IT` (integration tests usually
land in `:integrationTest`). To keep the harness simple, those four
hidden-test classes are renamed to `*Test` and authored with mocks
instead of a live DB. The corresponding YAML's `hiddenTest.class` and
`hiddenTest.file` get updated.

## Per-bug execution recipe (the playbook)

```
# From banking-app/, on main:
git checkout main
git pull --ff-only

# 1. Author the FIXED source + hidden test, committed to main.
${EDITOR} <filesTouched>          # ensure main has fix
${EDITOR} <hiddenTest.file>       # author the test
./gradlew :<module>:test --tests <hiddenTest.class>   # passes
git add . && git commit -m "BUG-NNNN: hidden test + canonical fix"

# 2. Update bug/BUG-NNNN/fix to reference main.
git branch -f bug/BUG-NNNN/fix main

# 3. Build the regression as a single commit on top of main.
git checkout -b bug/BUG-NNNN/break-build main
${EDITOR} <filesTouched>          # introduce the bug
git commit -am "BUG-NNNN: regress source to buggy version"
./gradlew :<module>:test --tests <hiddenTest.class>   # FAILS — confirms bug
git branch -f bug/BUG-NNNN/break bug/BUG-NNNN/break-build
git checkout main
git branch -D bug/BUG-NNNN/break-build

# 4. Verify through the harness.
#   POST /run/launch with bugId=BUG-NNNN, contextProvider=oracle, mode=OFF.
#   Expect: status=PASSED (LLM applies a fix that matches the canonical),
#   OR FAILED (model error) but reaching the test step (not no-tests-found).

# 5. Push.
git push origin main +bug/BUG-NNNN/break +bug/BUG-NNNN/fix
```

## Acceptance gates per bug

- [ ] `git show main:<hiddenTest.file>` shows the test source
- [ ] `git diff main..bug/BUG-NNNN/break -- <filesTouched>` shows the
      regression (1–10 lines)
- [ ] `git diff main..bug/BUG-NNNN/fix -- <filesTouched>` is empty (fix == main)
- [ ] On a fresh worktree at `bug/BUG-NNNN/break`,
      `:<module>:test --tests <hiddenTest.class>` exits non-zero with the
      expected assertion failure
- [ ] On the same worktree post-canonical-fix, the test passes
- [ ] One launch through the bench-webui (oracle context, mode=OFF) reaches
      the test step on the audit page (no `No tests found` message)

## Sequence

1. **POC: BUG-0001** end-to-end (today). Validates the playbook.
2. **Sweep: BUG-0002 → BUG-0012** (sequential; ~30–60 min each, can be
   parallelized across modules but I'll keep it serial to make
   verification clean).
3. **Permutation matrix**: 12 bugs × 4 contexts × 3 modes × 1 seed × 1
   model. ~144 runs. Tabulate pass-rate matrix; expect roughly
   `oracle ≈ navie >> bm25 >> none` and the mode dimension to make a
   minor difference (synthetic stubs don't add real signal).
4. **Audit**: per-run audit-page review. Compare gpt-4.1's diffs to the
   canonical fix. Document anomalies.
