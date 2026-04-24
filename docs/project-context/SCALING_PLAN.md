# Scaling plan — target 10M LOC

## Why 10M

Real megabank monorepos run 5M–20M LOC. To benchmark an LLM on "enterprise Java" we need the codebase to exceed the effective working context of frontier models by multiple orders of magnitude:

| Context / capacity | Approx LOC equivalent |
|---|---|
| Current banking-app | ~40K |
| Claude Opus 1M token window (raw) | ~500K |
| Target (this document) | **10M** |
| Reference: JPMorgan monorepo (internal estimates, ~2022) | 7–15M |

At 10M LOC the LLM cannot fit the codebase into any single prompt and must reason over a partial view — which is the realistic benchmarking condition.

## Why hand-writing doesn't work

Writing 10M LOC by hand at a sustainable 500 LOC/hr would take ~20,000 engineer-hours (~10 engineer-years). Not feasible. The codebase must be generated.

## Generation strategy

Four layers, in order of increasing fidelity:

### Layer 1 — Structural amplification (fast, low fidelity)

Take each existing enterprise pattern in `banking-app` and replicate it across realistic variations. Examples:

- **Product proliferation**: every service that today handles one product family gets variants for 20+ product types (checking / savings variants per region, per brand, per customer segment). Each variant produces ~80% the same code with different constants, validation rules, GL account mappings.
- **Region / locale**: replicate each major subsystem per jurisdiction (US federal + 50 states, UK/EU/APAC variants with local reg differences). Each adds a realistic fork of compliance, reg-reporting, statement rendering logic.
- **Channel splits**: web / mobile / branch / call-center / ATM / IVR / API variants of customer-facing services.
- **Legacy integration adapters**: mainframe bridge, COBOL-callout, flat-file EFT, SWIFT MT/MX, ISO 8583 card network connectors. Real banks have dozens of these.

A Gradle task (or standalone Kotlin script) reads a template and fills it per variant, producing new modules under `banking-app/generated/`. Output is real Java that compiles and passes tests.

**Estimated LOC contribution: 3–5M**

### Layer 2 — Enumerated domain entities (medium fidelity)

Banks carry enormous enumerated catalogs. Each entry is often a full class tree:

- **Products**: deposit/loan/card/investment products, each with rate tables, fee schedules, eligibility rules, disclosure templates. Hundreds of products in a real bank.
- **Regulatory reports**: FFIEC 031/041 Call Report, FRY-9C/14M/14Q, FR 2052a LCR, CCAR/DFAST stress tests, BCBS 239, OCC risk reports, FDIC assessment forms. Each report has dozens of schedules with line-item rules.
- **Message formats**: SWIFT (100+ MT types, 200+ MX types), ISO 20022 pacs/pain/camt subtypes, NACHA SEC codes, Fedwire formats.
- **Accounting schemes**: thousands of GL accounts with posting rules, chart-of-accounts hierarchies per entity.

Generate typed Java enums + associated rule classes from authoritative CSV/JSON inputs (SWIFT spec extracts, FFIEC MDRM, NACHA rulebook). Each produces realistic code that exercises switch/branch logic.

**Estimated LOC contribution: 2–3M**

### Layer 3 — Test proliferation (medium fidelity)

Every service should have a test class with ~15–30 scenarios. Today we have ~80 tests; at 10M LOC scale the bank would have 500K+ tests. Generate parameterized tests from scenario matrices:

- Each validator / scorer / pricer: amount-brackets × currency × product × jurisdiction.
- Each state machine: exhaustive transition and compensation coverage.
- Each batch job: happy path + 6–10 failure modes + 2–3 recovery modes.

**Estimated LOC contribution: 2M**

### Layer 4 — Legacy / deprecated code (low fidelity but authentic)

Real megabank codebases carry huge amounts of deprecated, partially-retired, or dark-deploy code that contributes to the LOC count but is rarely modified. Generate:

- Deprecated service interfaces kept for backwards compatibility.
- Former domain models shadow-copied when a new model was introduced.
- Disabled feature branches that still compile.

Marked with `@Deprecated` and `// DO NOT MODIFY — retired 20XX` comments so an LLM can't tell they're dead until it traces the callers.

**Estimated LOC contribution: 1M**

## Non-goals

- **Realism at every line** — nobody reads every line of a 10M LOC repo. What matters is that the *shape* is correct (module graph, naming, cross-cutting patterns), not that each line is original.
- **Runtime correctness of generated code** — it must compile and pass the tests it comes with, but not all generated scenarios need to be exercised in runtime.
- **Generation speed** — a one-time scaling run taking hours is fine. We only generate it when we update the benchmark.

## Acceptance criteria

- `find banking-app -name "*.java" | xargs wc -l` reports ≥ 10,000,000 total.
- `./gradlew :app-bootstrap:compileJava` completes within 30 minutes on reference hardware.
- Existing hand-written tests still pass (no regression from generated code colliding with existing names).
- The existing AppMap traces still render in the WebUI demo.

## Proposed generator implementation

- Location: `tooling/codegen/` (new directory), written as a standalone Kotlin Gradle task.
- Inputs:
  - `tooling/codegen/templates/` — Freemarker or Jinja templates per module archetype.
  - `tooling/codegen/spec/` — CSV/JSON specs defining product catalogs, regulatory schedules, etc.
- Outputs: `banking-app/generated/<moduleName>/` — new gradle subprojects auto-added to `settings.gradle.kts` by the generator.
- Idempotency: rerun of generator overwrites `generated/` but leaves hand-written modules untouched.
- CI: generator is NOT run in normal CI. It's invoked manually via `./gradlew :tooling-codegen:generate` when the benchmark is being updated.

## Ordered rollout

1. **Phase A** (next, after current test-coverage push): bootstrap the `tooling/codegen/` project, draft one template (a "product variant" archetype), generate ~10 variants as a proof of concept. Expected delta: 20K → 200K LOC.
2. **Phase B**: layer-2 enumerated domain entities, starting with SWIFT message handlers and FFIEC schedules. Expected delta: 200K → 2M LOC.
3. **Phase C**: layer-3 test proliferation. Expected delta: 2M → 4M LOC.
4. **Phase D**: layer-4 legacy shadows + additional regional/channel variants. Expected delta: 4M → 10M LOC.

Each phase should end with a green `./gradlew test` and a refreshed AppMap trace. If Phase A fails to produce useful AppMap coverage, stop and reassess before moving to B.

## Open questions

- Does the WebUI benchmark pick problems uniformly from the codebase, or from a curated subset? If curated, the generated modules can be shallower.
- Does AppMap's indexer scale to 10M LOC? May need to shard traces per top-level module.
- Reference hardware: do we have a CI machine with enough RAM to build this?
