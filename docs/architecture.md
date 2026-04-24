# Architecture

## Banking-app (Omnibank)

**Omnibank** is a fictitious full-service bank offering both consumer and corporate banking products. It is a multi-module Gradle project, Spring Boot 3.x, Java 21, intentionally engineered to mirror real enterprise banking code:

- Multi-module boundary discipline (every module has a public API package and internal impl package)
- Spring Boot auto-config contribution modules (autoconfigure + starter pairs for cross-cutting modules)
- JPA + Flyway migrations per bounded context
- Event-driven integration between modules via an internal message bus (in-JVM Spring Integration, with Kafka stubs for future wire replacement)
- Realistic concurrency: pessimistic locking on ledger postings, optimistic versioning on accounts, distributed locks (stubbed) for EOD batch
- Batch processing via Spring Batch (interest accrual, statement cycle, end-of-day)
- Caffeine caching, transaction boundaries that matter, N+1 traps the benchmark can exploit

### Module map

| Module | Role | Depth |
|---|---|---|
| `shared-domain` | `Money`, `AccountNumber`, `CustomerId`, value objects, currency math | flagship |
| `shared-persistence` | JPA base entities, auditing, soft-delete | support |
| `shared-security` | Auth filter, principal resolver | support |
| `shared-messaging` | Internal event bus abstraction | support |
| `shared-testing` | Fixtures, Testcontainers config | support |
| `ledger-core` | Double-entry general ledger, posting engine, trial balance | **flagship** |
| `accounts-consumer` | Checking, savings, certificates of deposit | **flagship** |
| `accounts-corporate` | Demand deposit, ZBA, sweep, lockbox | skeleton |
| `lending-consumer` | Mortgage, auto, personal loan, HELOC | skeleton |
| `lending-corporate` | Commercial loans, revolvers, syndication, covenants | **flagship** |
| `payments-hub` | ACH, Wire, RTP, FedNow, book transfer routing | **flagship** |
| `cards` | Card issuance, authorization, clearing, disputes | skeleton |
| `treasury` | Corporate cash management, FX, sweeps | skeleton |
| `fraud-detection` | Rules engine + scoring | skeleton |
| `compliance` | KYC, AML, OFAC screening, CTR/SAR | skeleton |
| `risk-engine` | Credit scoring, exposure limits | skeleton |
| `reg-reporting` | Regulatory reports (Reg CC, E, D, DD) | skeleton |
| `statements` | Statement generation, tax forms | skeleton |
| `notifications` | Email, SMS, push dispatch | skeleton |
| `audit-log` | Immutable tamper-evident audit trail | skeleton |
| `batch-processing` | EOD, interest accrual, statement cycle jobs | skeleton |
| `integration-gateway` | Stubbed adapters for FedLine, Clearinghouse | skeleton |
| `customer-portal-api` | REST API for web/mobile | minimal |
| `admin-console-api` | Back-office REST API | minimal |
| `app-bootstrap` | Spring Boot main application | minimal |

Flagship modules receive rich implementation — enough realistic business logic and cross-module coupling that injected bugs behave like enterprise bugs (cascading, non-obvious, testable). Skeletons get public API, key domain types, and TODO-marked impl stubs to be fleshed out as the bug catalog demands.

## Bench-harness

JVM libraries (Kotlin) that implement the evaluation machinery. Deliberately separate from `banking-app` — never import banking types.

```
bench-harness/
├── harness-core         Bug model, run orchestration, scoring metrics
├── harness-github       GitHub API client, repo scanner/ranker, OAuth device flow
├── harness-jira         JIRA REST client, ticket → problem-statement mapper
├── harness-builder      Gradle/Maven invoker with corporate proxy + Artifactory overrides
├── harness-appmap       AppMap agent wiring, trace collection, trace → context formatting
└── harness-llm
    ├── llm-api          Common LLM client abstraction
    ├── llm-copilot      Socket client to companion VSCode extension
    └── llm-corp-openai  Apigee-authed OpenAI-spec client with header injection
```

## Bench-webui

Lightweight Spring Boot app (HTMX + server-rendered Thymeleaf) providing:
- Dashboard: run history, pass rates by solver × AppMap on/off
- GitHub OAuth connect → repo scan → candidate ranking view
- JIRA credential configuration → queue selection
- LLM endpoint configuration (Copilot bridge health + Corporate API credentials)
- Benchmark run launcher

## Bench-cli

`picocli`-based CLI exposing the same harness operations for CI / scripting contexts. Subcommands mirror harness capabilities: `scan`, `build`, `solve`, `score`, `report`.

## Data flow (bespoke-bug run)

```
1. CLI: bench solve --bug BUG-0042 --solver corp-openai --appmap on
2. harness-core: checkout buggy commit in banking-app worktree
3. harness-builder: gradle build → green or fail-fast
4. harness-appmap (if on): run passing test suite, collect .appmap.json traces
5. harness-core: format problem statement from BUG-0042.yaml
6. harness-llm: send prompt + (AppMap traces as attachments) to solver
7. harness-core: receive patch, apply to worktree
8. harness-builder: gradle test (hidden test + regression suite)
9. harness-core: compute metrics → JSON report
```

## Data flow (enterprise-repo run)

```
1. User OAuths GitHub in webui
2. harness-github: scan accessible repos, score by language/size/activity/tests
3. User selects repo + connects JIRA + points at queue
4. harness-jira: fetch ticket, render problem statement
5. harness-builder: clone, configure proxy + ~/.gradle/gradle.properties Artifactory creds, build
6-9. Same as bespoke flow (AppMap trace collection runs against repo's own test suite)
```
