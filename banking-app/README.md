# Omnibank

Omnibank is a synthetic enterprise-Java banking application used as an evaluation target for AI coding agents (the `ai-bench` harness). It deliberately looks and feels like a megabank monorepo — a Spring Boot monolith broken into ~650 Gradle modules, with a mix of richly-implemented flagship domains, thin API-and-stub skeletons, and large volumes of generated ballast that mimic product variants, delivery channels, legacy subsystems, and payments-network adapters.

It is not a real bank and is not suitable for production use. Balances, rates, account numbers, and regulatory logic are stylised approximations that exist to give an agent realistic-looking ground to walk on.

## Why this exists

Most real banks carry a monorepo that is too big to fit in a single LLM context window, full of long-dead code nobody has the courage to delete, and full of near-duplicate modules driven by regulatory geography, product proliferation, and channel fragmentation. Omnibank reproduces that shape at reduced size so agents can be measured against it reproducibly:

- Enough real code to reason about (ledger, accounts, lending, payments).
- Enough ballast that "read every file" is not a viable strategy.
- Enough dead code, near-duplicates, and misleading naming that agents must actually trace call sites rather than pattern-match.

## Stack

| Layer | Choice |
|---|---|
| Language | Java 17 (toolchain-enforced; Gradle 8.14.4 runs on JDK 17-25 — OpenJDK, Oracle JDK, Temurin, Corretto, Zulu all supported) |
| Framework | Spring Boot 3.3.4 |
| Build | Gradle (Kotlin DSL), with `org.gradle.parallel` + configuration cache |
| Persistence | Spring Data JPA; H2 (dev default), PostgreSQL (profile `postgres`) |
| Migrations | Flyway, per-module script locations |
| Testing | JUnit 5, AssertJ, Spring Boot Test, Testcontainers BOM |
| Instrumentation | [AppMap](https://appmap.io/) Gradle plugin (optional, off by default) |

## Repository layout

```
omnibank/
├── app-bootstrap/                 Spring Boot entry point; wires every module
├── customer-portal-api/           Customer-facing REST surface
├── admin-console-api/             Internal ops REST surface
│
├── shared-domain/                 Cross-module domain types
├── shared-persistence/            JPA base classes, repositories, converters
├── shared-security/               Auth + RBAC primitives
├── shared-messaging/              Async messaging abstractions
├── shared-testing/                Test fixtures and helpers
│
├── ledger-core/                   Double-entry ledger, postings, trial balance
├── accounts-consumer/             Retail account lifecycle, holds, interest, fees
├── accounts-corporate/            Corporate account skeleton
├── lending-consumer/              Consumer lending skeleton
├── lending-corporate/             Corporate credit engine
├── payments-hub/                  ACH, wire, RTP, FedNow routing
├── cards/                         Card auth, tokenization, disputes, rewards
├── treasury/                      Liquidity / FX skeleton
├── fraud-detection/               Rules, velocity, device trust skeleton
├── compliance/                    KYC, OFAC/sanctions, AML, CTR
├── risk-engine/                   VaR, stress, exposure, counterparty risk
├── reg-reporting/                 Call reports, HMDA, CCAR, LCR
├── statements/                    Rendering, archive, delivery
├── notifications/                 Email / SMS / push dispatch
├── audit-log/                     Append-only audit trail
├── batch-processing/              EOD close, reconciliation, job orchestration
├── integration-gateway/           SFTP, MQ, mainframe, SOAP/REST facades
│
├── generated/                     51 product-variant modules  (codegen)
├── generated-brands/              89 brand-scoped product forks (codegen)
├── generated-channels/            120 channel adapters — web/mobile/ATM/IVR/... (codegen)
├── generated-regional/            250 state-by-state regional forks (codegen)
├── generated-nacha/               24 NACHA SEC-code handlers (codegen)
├── generated-swift/               46 SWIFT MT message handlers (codegen)
└── generated-legacy/              51 "retired" subsystems kept in-tree as ballast (codegen)
```

Totals: ~30 hand-authored modules + ~630 generated modules, all wired into a single `settings.gradle.kts`.

### Module conventions

- `api/` — public types other modules may depend on (services, DTOs, events).
- `internal/` — implementation detail; do not reach in from outside the module.
- Flyway scripts live under `src/main/resources/db/migration/<module_name>/`.
- AppMap is configured via the root `appmap.yml`; `shared-testing` and `*.api.dto` packages are excluded from capture.

### Generated modules

Anything under `generated*/` is produced by a code generator under `tooling/codegen/` and is **not hand-edited**. Each directory has its own README describing the driver CSV and the generator class (for example `generated/README.md`, `generated-legacy/README.md`). If you need to change them, change the generator input and re-run, do not patch outputs.

`generated-legacy/` is intentionally deceptive ballast: the modules compile, are wired into Gradle, carry plausible `@Deprecated` annotations and stale ticket references, and look like the kind of retired subsystem a megabank would still be unable to delete years after replacing it. Tracing call sites is the only reliable way to know a legacy-shadow module is dead.

## Getting started

### Prerequisites

- JDK 17 through 25 (the Gradle toolchain pins source/target to 17 for cross-compat; 17 / 21 / 25 all work — including Oracle JDK 25. Gradle will auto-download a JDK 17 via the Foojay resolver if the default on `PATH` is a different major version).
- No external services required for the default dev profile — H2 runs in-process.

### Build everything

```bash
./gradlew build
```

### Run the banking app

```bash
./gradlew :app-bootstrap:bootRun
```

Then visit:

- `http://localhost:8080/` — HTML landing page with auth status, balance lookup, endpoint catalog, and curl recipes. Sending `Accept: application/json` returns a machine-readable service-info document instead.
- `http://localhost:8080/actuator/health` — public health probe.
- `http://localhost:8080/api/v1/accounts/{accountNumber}/balance` — authenticated balance lookup.
- `http://localhost:8080/api/v1/payments` — authenticated payments API.

### Authentication

The demo uses Spring Security's default in-memory user. The generated password is printed to the app log at startup:

```
Using generated security password: <password>
```

Use HTTP Basic with username `user` and that password, or drive the app via the `ai-bench` WebUI's auto-login bridge (`/_demo/autologin`). See `app-bootstrap/src/main/java/com/omnibank/DemoSecurityConfig.java` for the filter chain; everything under `/api/**` requires auth, while `/`, `/actuator/**`, `/error`, `/favicon.ico`, `/_demo/autologin`, and `/_appmap/**` are public.

### Switch to PostgreSQL

```bash
SPRING_PROFILES_ACTIVE=postgres ./gradlew :app-bootstrap:bootRun
```

Expects a reachable `jdbc:postgresql://localhost:5432/omnibank` with user/password `omnibank`/`omnibank`. See `app-bootstrap/src/main/resources/application.yaml` to adjust.

### Tests

```bash
./gradlew test                                    # whole tree
./gradlew :accounts-consumer:test                 # one module
./gradlew :app-bootstrap:test --tests '*Flow*'    # end-to-end flow tests
```

Flow tests under `app-bootstrap/src/test/java/com/omnibank/functional/` exercise cross-module scenarios (customer onboarding, payment processing, wire lifecycle, card spending).

### AppMap tracing

AppMap instrumentation is off by default. Turn it on for a run:

```bash
./gradlew -Pappmap_enabled=true :accounts-consumer:test
```

Captured traces land under `tmp/appmap/` and are ignored by git. `app-bootstrap` forwards `-PjvmArgs="..."` onto `bootRun` so the WebUI's "Start banking app with AppMap agent" button can attach the agent at runtime.

## Working in this repo

- Start from `app-bootstrap` to see how the monolith is wired.
- Real business logic lives in `ledger-core`, `accounts-consumer`, `lending-corporate`, and `payments-hub` — these are the "flagship" modules. Other business modules publish APIs and stub implementations.
- Before modifying code under `generated*/`, confirm whether it's actually on a live call path — much of it is not.
- Module boundaries are enforced only by package conventions, not by the build. Please keep `internal/` types out of other modules' imports.

## License

Not licensed for external reuse — synthetic benchmark target only.
