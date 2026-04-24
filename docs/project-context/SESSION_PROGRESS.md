# Session Progress — 2026-04-16

Fine-grained tracker so work is restartable if the session budget runs out.
Update after each meaningful step. Newest entries at top.

## Commits this session (newest first)

- `cb3cd5e` — **Phase C Layer 3 part 3**: SwiftScenarioTestGenerator
  (46 modules, ~7.9K LOC) + NachaScenarioTestGenerator (24 modules,
  ~4.2K LOC). Banking-app: 461K → 474K LOC, 1,155 *Test.java files,
  529 of which are auto-generated scenario tests across all five
  generated module families (regional/channel/brand/swift/nacha).
- `47d396e` — **Phase C Layer 3 part 2**: ChannelScenarioTestGenerator
  (120 modules) + BrandScenarioTestGenerator (89 modules). +18.8K LOC
  channel tests + 15K LOC brand tests = ~34K LOC. Banking-app:
  428K → 461K LOC, 1,085 *Test.java files (5,384 total Java files).
- `6c06867` — **Phase C Layer 3**: RegionalScenarioTestGenerator emits
  20 additional @Test methods per regional module (×250 modules) using
  only existing public APIs. +5,000 tests, +42K LOC of test code.
  banking-app: 385K → 428K LOC, 876 *Test.java files.
- `ef30b08` — **Phase D Layer 4**: LegacyShadowGenerator + 51 retired
  modules under banking-app/generated-legacy/. +21K LOC, 606 → 657 modules.
- `aaff670` — ProxyConfigController + ResultsController tests (5 tests)
- `63f1ca5` — harness-config (SecretSource, BenchConfig) + bench-webui
  (RunLauncher, JiraConfig) tests; **fixed silent JUnit5 misconfig in
  bench-webui** (`useJUnitPlatform()` was missing — webui's existing
  controller tests were never running)
- `95ec68a` — Service tests for accounts/ledger/compliance/fraud (7 new
  test classes, ~80 tests). Exposed and fixed **4 production bugs**:
  AccountStateMachine DORMANT routing, PostingRuleEngine revenue sign,
  KycWorkflowEngine document-age severity downgrade, KycWorkflowEngine
  RISK_ASSESSMENT chicken-and-egg
- `609239d` — cards/risk/reg-reporting/statements modules + tests; fixed
  FfiecCallReportGenerator equity derivation and findBalance aggregation
- `7126c97` — session progress refresh
- `d010f2a` — NACHA SEC-code processor modules (24 codes, +8K LOC)
- `5f7b601` — SWIFT MT message handlers (46 message types, +22.5K LOC)
- `a6bbc24` — BrandVariantGenerator + 89 brand forks (+35K LOC)
- `8762e43` — ChannelVariantGenerator + 120 channel forks (+65K LOC)
- `639caa8` — **Phase A continued**: RegionalForkGenerator + 250 state-level forks (+127K LOC, +1750 tests)
- `d1f3f77` — Expanded product catalog to 51 variants (+38K LOC)
- `7a14bf0` — **Phase A scaling**: code generator + 10 product-variant modules (+9.3K LOC, +120 tests)
- `946c238` — Revised scaling target to 10M LOC; documented four-layer generator plan
- `1def0c6` — AmlTransactionMonitor tests (12 tests): BSA typologies, SAR filing, alert closure
- `636cfe1` — TransactionRiskScorer tests (10) + cross-module PaymentProcessingFlowTest (5)
- `87094c1` — AccountFeeEngine tests (10): maintenance/min-balance/paper-statement/reversal
- `0285549` — JournalEntryValidator tests (17): all 13 validation rules
- `09f6b7b` — PaymentLifecycleManager (15) + PaymentRoutingEngine (13) tests; cards API start
- `3e086d7` — startup fixes (auto-config exclusions, @Service dedup, resilience beans)

**Banking-app state: 345,196 LOC / 4,492 files / 580 Gradle modules / ~2700 tests** (from 38K / 275 / 25 / 3 baseline). All green under `./gradlew test` in ~28s.

**Six code generators in `tooling/codegen/` — all validated end-to-end:**
- `ProductVariantGenerator` (Layer 1) — 51 product catalog modules
- `RegionalForkGenerator` (Layer 1) — 5 products × 50 states = 250 forks
- `ChannelVariantGenerator` (Layer 1) — 15 products × 8 channels = 120 forks
- `BrandVariantGenerator` (Layer 1) — 7 brands × ≤20 products = 89 forks
- `SwiftMessageHandlerGenerator` (Layer 2) — 46 SWIFT MT message handlers
- `NachaSecCodeGenerator` (Layer 2) — 24 NACHA SEC-code processors

## Done

- [x] Startup errors fixed; TODO.md items 1 & 2 checked off.
- [x] AppMap log gitignored; session progress file created.
- [x] Cards module: API types added (CardNetwork, CardStatus, CardToken).
- [x] Broad unit test coverage for: payments-hub (lifecycle, routing), ledger-core (validator), accounts-consumer (fee engine), fraud-detection (risk scorer), compliance (AML monitor).
- [x] Cross-module functional test (PaymentProcessingFlowTest) exercises fraud + lifecycle + routing in a single call tree.
- [x] `./gradlew test` on banking-app is green across all modules.

## In progress

- [ ] Scale codebase further — **target revised 2026-04-16 to 10M LOC** (see `SCALING_PLAN.md`). Hand-writing infeasible at this magnitude; plan is a four-layer generator (structural amplification / enumerated domain entities / test proliferation / legacy shadows). Current: ~40K LOC source + ~1.5K LOC tests.

## Queued (ordered)

1. **Phase A of scaling plan**: bootstrap `tooling/codegen/`, draft one template (product variant archetype), generate ~10 variants as proof of concept (20K → 200K LOC delta).
2. Expand cards module: CardAuthorizationEngine, CardTokenizationService, CardSettlementProcessor, CardDisputeManager, CardRewardsCalculator, CardLifecycleManager, CardFraudRuleEvaluator.
3. Expand risk-engine, statements, reg-reporting stub modules (provides more templates to amplify in later phases).
4. Additional service tests: PostingRuleEngine, TrialBalanceEngine, PeriodCloseManager, VelocityCheckEngine, KycWorkflowEngine, InterestCalculationEngine, AccountStateMachine.
5. bench-webui additional controller tests + harness-config tests.
6. **Phase B/C/D of scaling plan** (later sessions): enumerated domain entities → test proliferation → legacy shadows.

## Notes / decisions

- Test strategy: each test class targets a real service class, uses in-memory repo stubs so the Spring context doesn't need to boot. This keeps AppMap traces focused on the banking logic, not infrastructure.
- Functional tests live in `app-bootstrap` since that module aggregates all others and can wire cross-module flows without circular gradle deps.
- Made `TransactionRiskScorer.TransactionContext` public so cross-module tests can construct it (prior state was package-private).
- Account number format is `OB-[CRML X T]-[8 alphanumeric]`; routing numbers pass ABA checksum. Use those or tests fail at the domain-constructor boundary.
- GlAccountCode format is `^(ASS|LIA|EQU|REV|EXP)-[0-9]{4}-[0-9]{3}$` — lowercase or non-digit suffixes fail validation.

## Restart checklist

1. `git log --oneline -8` — last commit should be `1def0c6` or newer.
2. `git status` — check for uncommitted work.
3. Read this file top-to-bottom for the active task and its context.
4. Read `memory/project_state.md` for the higher-level narrative.
5. Resume the top "In progress" task or pick next from "Queued".
6. Run `./gradlew test` from inside `banking-app/` to confirm green baseline.
