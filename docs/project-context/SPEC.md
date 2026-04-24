# Original specification (captured verbatim + interpretation)

This file captures the user's original spec as given over two prompts, plus the interpretation decisions made while scaffolding. It exists so a new session (human or AI) can pick this project up cold without re-deriving intent.

## User's original prompts

**Prompt 1 — initial request:**

> Create a new project that will be for benchmarking AI effectiveness and efficiency. It will create a large, complex, Java application that mirrors a large scale and complex enterprise application that is fully self-contained so we can effectivelely build and test. We will then plan to intentionally inject bugs into the commit timeline, along with future fixes that include new units tests that verify the fixes. This sample app will serve as the baseline for an internal 'SWEBench' style of testing but one that will use an app that models are assured not to have been trained on. We will plan to feed the set of problems to AI solvers, then measure token usage, code complexity, and addition of bugs elsewhere in the code as part of the evaluation. We will also compare the solver effectiveness with and without AppMap traces

**Prompt 2 — refinements (answers to my planning questions):**

> 1 - Domain, see what domain we can use that fits for a large financial services company that is primarily focused on consumer and corporate banking.
> 2. Size target, go bigger.
> 3. Generate the full thing in one pass.
> 4. Build a separate lightweight WebUI that also has a CLI
> 5. Put it in ~/VSCodeProjects/ai-bench-java
> 6. As part of the separate lightweight 'management' webui, build functionality that takes a user's corporate GitHub login and scans available repositories that user has access to to determine the best target repositories that would also be the best 'real world' enterprise benchmarks. as part of this also provide a method for a user to provide a JIRA login and associate a specific JIRA queue for tracking issues against an enterprise code base, as many corporate repositories may not use integrated GitHub issues to track bugs. Finally, build a test harness that can download the GitHub projects from the enterprise repository, build it locally (accounting for custom enterprise proxy and artifactory build configurations), and then attempt solving bugs with and without AppMap, measuring solving effectiveness and efficiency. For enterprise LLM access, plan to offer a method for users to use GitHub Copilot integrated within VScode, as well as a corporate AI API that is similar to the Open AI API spec, however, it first requires Apigee authentication and then also requires custom headers to be added to API requests.

**Prompt 3 — mid-build clarification:**

> ensure that all context and memory related to this effort is saved in the project directory so it can be restarted if interrupted

## Interpretation / derived decisions

### Domain selection

Target "large financial services company, consumer + corporate banking."

**Chosen codename:** **Omnibank** (fictitious unified full-service bank).

Modules reflect both consumer and corporate banking concerns, plus the cross-cutting infrastructure that a real bank's monolith (or loosely-coupled modulith) would include. Rationale: picking both consumer AND corporate gives richer domain complexity and more bug surface than either alone. Banking-specific intentional choices:
- Double-entry ledger as a first-class flagship module (posting engine = fertile bug ground)
- Commercial lending as a flagship (state machines, covenants, syndication = cross-cutting bug ground)
- Payments hub covering ACH/Wire/RTP/FedNow (timing, routing, cutoffs = great AppMap-demonstration targets)

### Size

User said "go bigger" than the initial 40–60k LOC estimate. Target raised to **~80–150k LOC** across ~24 banking-app modules + ~10 harness modules. Flagship modules get real implementation depth; skeletons get public API + key domain types so bugs can be injected by filling them in over time.

### One-pass execution

User said "generate the full thing in one pass." Interpreted as: **one coordinated build session**, not literally one turn — the scope vastly exceeds single-turn output. Proceeding multi-turn but without stopping for further re-approval on scope/shape.

### Dual purpose

Re-reading prompt 2 carefully, this is a **dual-purpose project**:

1. **Bespoke baseline** — the banking app (Omnibank) as an uncontaminated benchmark
2. **Real-world harness** — same machinery pointed at actual enterprise repos the user has access to

The harness must work on both. Notably: the enterprise arm needs JIRA-as-issue-source (not just GitHub issues), corporate proxy/Artifactory builds, and the enterprise LLM adapters (Copilot + Apigee'd OpenAI-spec).

### Components & ownership

| Component | Language | Role |
|---|---|---|
| `banking-app/` | Java 21 / Spring Boot 3 / Gradle | Target enterprise app |
| `bench-harness/` | Kotlin / Gradle | Eval machinery libs |
| `bench-cli/` | Kotlin + picocli | Command-line entry |
| `bench-webui/` | Kotlin + Spring Boot + Thymeleaf + HTMX | Management UI |
| `bugs/` | YAML | Bug metadata catalog |
| `tooling/vscode-copilot-bridge/` | TypeScript | VSCode extension for Copilot access |

Kotlin for harness/webui is chosen deliberately to:
- Keep target app (Java) and harness (Kotlin) visually/statically separable
- Get coroutines for concurrent LLM orchestration
- Get better DSLs for config overlays

### LLM adapters

- **GitHub Copilot**: socket-bridge to a companion VSCode extension that uses `vscode.lm` API. Piggybacks on the user's existing Copilot entitlement.
- **Corporate OpenAI-spec (Apigee'd)**: OAuth2 client_credentials → bearer token, cached; custom headers injected per-request (`X-Correlation-Id`, `X-Client-Id`, `X-Environment`, `X-Use-Case` + user-configurable); corporate truststore respected; HTTPS proxy respected.

### AppMap integration

AppMap Gradle plugin applied to the banking-app. `tmp/appmap/*.appmap.json` collected after armed test runs. Three injection strategies: raw attach, compact, focused. Per-run metadata records which was used so we can correlate uplift by strategy.

### Evaluation dimensions

Exactly as user listed, plus a couple of derived ones:
- pass@1, pass@k
- token usage (prompt + completion)
- wall-clock time
- cyclomatic / cognitive complexity delta on touched files
- regression introduction (full-suite delta)
- patch minimality (LOC changed vs. oracle patch, bespoke arm only)
- AppMap uplift (Δ across all above, with vs. without)

## Explicit non-goals

- Not production-deployable banking software. Security, compliance, regulatory correctness are simulated, not real.
- Not a general test-harness framework. Scope is AI-solver evaluation with AppMap ablation.
- Not trying to match SWE-Bench's exact scoring formula — our metrics are a superset.
