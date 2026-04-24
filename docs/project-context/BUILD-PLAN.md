# Build plan (with status)

Authoritative status list for the initial build. Update this as tasks complete. The intent is: a resuming session can read this file + `RESUME.md` + the code as it exists on disk, and have enough to pick up.

## Status legend

- `[ ]` not started
- `[~]` in progress
- `[x]` complete

## Phase 0 — Top-level context & docs

- [x] Top-level `README.md`
- [x] `docs/architecture.md`
- [x] `docs/bug-catalog.md`
- [x] `docs/eval-protocol.md`
- [x] `docs/llm-integrations.md`
- [x] `docs/enterprise-builder.md`
- [x] `docs/appmap-integration.md`
- [x] `docs/project-context/SPEC.md`
- [x] `docs/project-context/BUILD-PLAN.md` (this file)
- [x] `docs/project-context/RESUME.md`
- [x] `docs/project-context/DECISIONS.md`

## Phase 1 — banking-app scaffolding

- [x] `banking-app/settings.gradle.kts` (all 25 subprojects)
- [x] `banking-app/build.gradle.kts` root (plugins, java toolchain, AppMap plugin decl)
- [x] `banking-app/gradle.properties`
- [x] `banking-app/appmap.yml`
- [x] `banking-app/.gitignore`
- [x] Per-module `build.gradle.kts` for all 25 subprojects
- [x] Gradle wrapper generated (8.10) for banking-app, bench-harness, bench-webui, bench-cli.

## Phase 2 — shared modules (support, terse impl)

- [x] `shared-domain` — `Money`, `AccountNumber`, `CustomerId`, currency math, value objects
- [x] `shared-persistence` — JPA base entity, auditing listener, soft-delete
- [x] `shared-security` — auth filter interface, principal resolver
- [x] `shared-messaging` — internal event bus facade (Spring Integration backed)
- [x] `shared-testing` — Testcontainers config, fixtures

## Phase 3 — flagship modules (rich impl — where most bugs will live)

- [x] `ledger-core` — double-entry GL, posting engine, trial balance, journal, accounts
- [x] `accounts-consumer` — checking / savings / CD lifecycle, balance holds
- [x] `lending-corporate` — commercial loan lifecycle, covenants, syndication, draws, amortization
- [x] `payments-hub` — ACH, Wire, RTP, FedNow, book transfer, cutoffs, routing

## Phase 4 — skeleton modules (public API + key types, impl stubs)

- [x] `accounts-corporate`
- [x] `lending-consumer`
- [x] `cards`
- [x] `treasury`
- [x] `fraud-detection`
- [x] `compliance`
- [x] `risk-engine`
- [x] `reg-reporting`
- [x] `statements`
- [x] `notifications`
- [x] `audit-log`
- [x] `batch-processing`
- [x] `integration-gateway`

## Phase 5 — app edges

- [x] `customer-portal-api` — `AccountController`, `PaymentController`
- [x] `admin-console-api` — `AdminAccountController`, `LedgerInquiryController`
- [x] `app-bootstrap` — `OmnibankApplication` + `application.yaml` (dev + postgres profiles) + banner

## Phase 6 — bench-harness

- [x] `bench-harness/settings.gradle.kts`, root `build.gradle.kts`, `gradle.properties`
- [x] `harness-core` — `Bug`, `BugCatalog`, `RunOrchestrator`, SPI contracts, `PromptAssembler`, `JGitWorktree`
- [x] `harness-github` — `GitHubDeviceFlow`, `RepoScanner`
- [x] `harness-jira` — `JiraClient`, `JiraProblemSource`
- [x] `harness-builder` — `EnterpriseBuildConfig`, `GradleInitScriptWriter`, `GradleBuilder`
- [x] `harness-appmap` — `AppMapCollectorImpl`, `TraceCompactor`
- [x] `harness-llm/llm-api` — `LlmClient` interface, `LlmClientFactory` (ServiceLoader)
- [x] `harness-llm/llm-copilot` — `CopilotSocketClient` + `CopilotProvider` + META-INF/services
- [x] `harness-llm/llm-corp-openai` — `CorpOpenAiConfig`, `ApigeeTokenProvider`, `CorpOpenAiClient`, `CorpOpenAiProvider` + META-INF/services

## Phase 7 — bench-cli

- [x] `bench-cli/build.gradle.kts` + `settings.gradle.kts` (composite build including harness)
- [x] Subcommands: `catalog`, `solve`, `scan`, `build`, `report` (wiring + picocli plumbing; integration calls stubbed with TODO pointers)

## Phase 8 — bench-webui

- [x] Spring Boot + Thymeleaf + HTMX setup
- [x] Dashboard view (runs, pass rates)
- [x] GitHub connect → scan → rank view
- [x] JIRA credential + queue select view
- [x] LLM endpoint config view
- [x] Run launcher view
- [x] Shared stylesheet

## Phase 9 — VSCode bridge

- [x] `tooling/vscode-copilot-bridge/` TypeScript extension
- [x] `package.json`, `tsconfig.json`, `src/extension.ts` (Unix-socket server + `vscode.lm` relay)
- [x] README with protocol docs

## Phase 10 — bug catalog samples

- [x] 12 sample `BUG-xxxx.yaml` files spanning all difficulty tiers + categories
- [x] Paired break/fix git commits in banking-app: all 12 bugs (BUG-0001 through BUG-0012) have break/fix branches with real code changes in alfredsisley10/omnibank-demo.

## Phase 11 — smoke verification

- [x] `./gradlew build` in banking-app: all 25 modules compile, 9 tests pass.
- [x] `bench-harness` compiles (all 9 modules including harness-config).
- [x] `bench-cli` compiles via composite build.
- [x] `bench-webui` starts on :7777 with 18 passing tests.
