# Architectural decisions

Non-obvious choices and their reasons. ADR-flavored but terser. Each decision is load-bearing — if you change it, understand what it affected.

## D-001 — Codename "Omnibank"

Fictitious full-service bank serving both consumer and corporate. Unified name (not split per segment) because the whole point is a single monolithic codebase with cross-cutting bug opportunities.

## D-002 — Java 21 for banking-app, Kotlin for harness

**Banking-app is Java** because the benchmark targets enterprise Java realism; idiomatic Kotlin code would be too easy to identify as "not real enterprise Java." Java 21 specifically: records, sealed types, pattern matching, virtual threads are fair game and present in modern enterprise stacks.

**Harness/CLI/WebUI are Kotlin** because (a) it's cleanly separable from the target app, (b) coroutines simplify concurrent solver orchestration, (c) Kotlin DSLs make config overlays readable. The harness never leaks into the target app.

## D-003 — Multi-module Gradle over monomodule

Real enterprise banking apps have module boundaries. Multi-module forces us to author realistic cross-module coupling that bugs can exploit. Single-module would make the benchmark too easy.

## D-004 — Spring Boot 3.x monolith, not microservices

A single-JVM multi-module monolith is easier to build/test reliably, keeps evaluation fast, and still yields plenty of cross-module bug surface. Microservices would introduce too much infra churn and make AppMap's per-process tracing view harder to compose. We reserve the right to split specific modules into services later if evaluation demands it.

## D-005 — Double-entry ledger as flagship

Every bank has one. It's numerically precise, prone to subtle bugs (rounding, sign flips, account-type direction, locking), and maps directly onto well-known accounting invariants (debits = credits, trial balance zero). Rich ground for realistic bugs that can be verified by strong property-based tests.

## D-006 — Commercial lending as flagship (not just consumer lending)

Commercial lending has state machines (origination → underwriting → approval → draw → servicing → payoff), covenants (financial + behavioral), syndication (lead + participants), and draw schedules. Much richer than mortgage/auto, and less public tutorial material exists — good for "not in training data."

## D-007 — Payments hub covers ACH/Wire/RTP/FedNow

All four payment rails have distinct cutoff, routing, reversibility, and settlement semantics. Many realistic bugs live at rail-specific edge cases (ACH same-day cutoff, wire cutoff timezone, RTP idempotency). These are textbook AppMap-benefits-from-runtime-context scenarios.

## D-008 — Flagship vs. skeleton module strategy

Richly implementing 24 modules in one pass is impractical. Instead:
- **Flagship modules** (4): `ledger-core`, `accounts-consumer`, `lending-corporate`, `payments-hub`. Rich domain logic, realistic cross-file coupling. Most bugs target these.
- **Skeleton modules** (~19): public API + key domain types + stubbed impl. Filled in incrementally as bug catalog demands.

Rationale: concentrates effort on modules that will carry most of the benchmark weight, while leaving the full module graph intact so the codebase "feels" enterprise-large.

## D-009 — AppMap is opt-in per run, not default-on

Default test runs don't emit traces (perf penalty, disk cost). Traces are collected only when the harness arms them for a given run, via env var `ORG_GRADLE_PROJECT_appmap_enabled=true`. Keeps the baseline build fast; forces AppMap to be explicit in every armed run (and thus observable in run metadata).

## D-010 — GitHub Copilot integration via VSCode socket bridge

Copilot has no stable public HTTP API. The reliable path is `vscode.lm` in a VSCode extension. A Unix socket bridge (`tooling/vscode-copilot-bridge/`) exposes it to the JVM harness. This:
- Piggybacks on user's existing Copilot entitlement (no separate credentials)
- Avoids HTTP port allocation (corp VSCode may lock down loopback binding)
- Uses filesystem perms as auth boundary (only same-user connections allowed)

Limitation: requires VSCode running. Documented in `docs/llm-integrations.md`.

## D-011 — Corporate OpenAI adapter does Apigee OAuth2 + header injection

Common enterprise pattern: Apigee fronts an OpenAI-spec inference endpoint. Client must:
1. OAuth2 client_credentials → bearer token (cache to TTL - 60s)
2. Inject per-request custom headers (correlation-id, client-id, env, use-case)
3. Trust a corporate CA (no global JVM truststore mutation — use per-client `SSLContext`)
4. Respect `HTTPS_PROXY` / `NO_PROXY`

Config YAML at `~/.ai-bench/corp-openai.yaml`. Secrets pulled from env vars, never from the YAML itself.

## D-012 — Enterprise builder overlays, does not overwrite

`harness-builder` needs to point Gradle/Maven at internal Artifactory / Nexus. Instead of overwriting `~/.gradle/gradle.properties`, it writes an init script to `~/.gradle/init.d/ai-bench-enterprise.gradle.kts` and a scoped `settings-ai-bench.xml` for Maven. Removes itself cleanly after the run. Never mutates user's global config in-place.

## D-013 — Bug storage: YAML metadata + paired git commits

Each bug is:
- `bugs/BUG-NNNN.yaml` — metadata (problem statement, difficulty, hidden test, oracle diff lines, appmap focus)
- Commit `bug/BUG-NNNN/break` in banking-app — introduces the bug
- Commit `bug/BUG-NNNN/fix` in banking-app — fixes the bug + lands the hidden test

The harness uses git tags (or named branches) to navigate between them. Paired commits over patchfiles because the git graph itself is part of the benchmark.

## D-014 — Enterprise repo arm reuses the same orchestrator

Same `RunOrchestrator` drives both bespoke-bug runs and enterprise-repo runs. The difference is just the source of the worktree and problem statement:
- Bespoke: banking-app at `bug/BUG-NNNN/break`, problem from YAML
- Enterprise: cloned repo at a user-chosen commit, problem from JIRA ticket

This keeps scoring and reporting paths identical and simplifies cross-comparison.

## D-015 — No database service in banking-app by default

Default profile uses H2 in-memory (file-per-module schema). Postgres profile exists and is Testcontainers-wired for integration tests and production-shape runs. This keeps smoke builds fast and portable.

## D-016 — Bench-webui: server-rendered Thymeleaf + HTMX, not SPA

For a management UI at this scale, a SPA would be overkill. Thymeleaf + HTMX gives interactivity with trivial backend-fronted state. Also keeps the stack Kotlin+JVM end-to-end — no separate frontend build.

## D-017 — Per-project context capture in `docs/project-context/`

Because this project is too large to build in one conversation, we persist state inside the repo itself (`SPEC.md`, `DECISIONS.md`, `BUILD-PLAN.md`, `RESUME.md`, ephemeral `IN-PROGRESS.md`). Any session (human or AI) can pick up where the previous one stopped by reading these four files.
