# ai-bench-java

Internal SWE-Bench-style benchmark platform for evaluating AI coding
assistant **effectiveness** and **efficiency** on enterprise-scale Java
applications — with particular focus on measuring the impact of
[AppMap](https://appmap.io) runtime traces as solver context.

> **Status — active initial build.** The benchmarking tools (`bench-cli`,
> `bench-webui`) are functional; the bug catalog and `banking-app`
> modules are still landing. CLI subcommands like `bench solve` /
> `bench report` print TODO markers until the harness wires them up.

## Why this exists

Public SWE-Bench relies on open-source repositories that mainstream LLMs
have been trained on. Results are contaminated: models may have memorized
fix patches rather than reasoned about them. This project addresses that
with a **dual strategy**:

1. **Bespoke baseline** — a large-scale Java banking application
   (**Omnibank**) modeling consumer + corporate banking. Models are
   assured not to have been trained on it. Bugs are intentionally
   injected along the commit timeline, with oracle fix commits and
   hidden unit tests. Clean signal.

2. **Real-world harness** — the same evaluation machinery can be pointed
   at actual enterprise GitHub repositories the user has access to.
   Scans repos the user can see, identifies good benchmark candidates,
   pulls JIRA tickets as problem statements, builds behind corporate
   proxy/Artifactory, scores solvers. Transitions evaluation from a
   synthetic baseline to the actual codebases that matter.

## Evaluation dimensions

| Metric | Why we measure it |
|---|---|
| **pass@1 / pass@k** | Basic solver effectiveness — did it fix the bug? |
| **Token usage** (prompt + completion) | Efficiency, cost, practicality at scale |
| **Wall-clock time** | Developer-experience proxy |
| **Cyclomatic / cognitive complexity delta** | Did the fix make the code worse? |
| **Regression introduction** | Did the fix break something else? (full suite runs) |
| **Patch minimality** | LOC changed vs. oracle patch |
| **AppMap uplift** | Δ on all above metrics with AppMap traces available vs. not |

## Top-level layout

```
ai-bench-java/
├── banking-app/       Target enterprise Java app (Omnibank) — multi-module Spring Boot
├── bench-harness/     Evaluation tooling libraries (JVM)
├── bench-webui/       Lightweight management web UI (Spring Boot + HTMX)
├── bench-cli/         CLI entry point (picocli)
├── bugs/              YAML bug catalog (BUG-xxxx.yaml)
├── dist/              Pre-built bench-cli + bench-webui artifacts
├── docs/              Architecture, protocols, integration guides
└── scripts/           Build + run helpers
```

## Quick start

### Prerequisites

- **JDK 17 through 25** on `PATH`. OpenJDK, Eclipse Temurin, Oracle JDK,
  Amazon Corretto, and Azul Zulu all work. The toolchain is pinned to 17
  for source-level cross-compatibility; the bundled Gradle 9.4.x wrapper
  runs on any JDK in this range.
- **Git** for checkout.
- No separate Gradle install needed — every sub-project ships its own
  wrapper.

### Run the pre-built tools (recommended)

`bench-cli` and `bench-webui` are committed to `dist/` in this repo
**and** published to the [GitHub Releases page][gh-releases], so most
users do **not** need to build them. The `banking-app` is intentionally
**not** prebuilt — it's the thing under evaluation, and the harness has
to compile and test it locally.

```bash
# macOS / Linux
./scripts/start-bench-tools.sh
```

```powershell
# Windows (PowerShell)
.\scripts\start-bench-tools.ps1
```

The script verifies a JDK 17–25 is on `PATH`, copies pre-built artifacts
from `dist/` (or downloads from GitHub Releases if `dist/` is empty),
unzips `bench-cli`, launches `bench-webui` in the background on
http://localhost:7777, and prints the `bench-cli` launcher path.
Re-running is safe: an already-running `bench-webui` is detected via
PID file and the script exits without launching a duplicate.

> **Install location.** The script creates a fresh subdirectory at
> `~/ai-bench` (macOS / Linux) or `%USERPROFILE%\ai-bench` (Windows)
> and unpacks everything there. **Nothing else on your machine is
> touched.** Override with `INSTALL_DIR=/some/path` (bash) or
> `-InstallDir D:\some\path` (PowerShell) if `~/ai-bench` collides
> with something you already have. The script announces the path
> before it creates anything so a fresh-install operator can confirm
> first.
>
> What lands in the install dir:
>
> | File / directory | Purpose |
> |---|---|
> | `bench-cli-*.zip` | The downloaded distribution |
> | `bench-cli-*/bin/bench-cli` (or `.bat`) | CLI launcher — add `bin/` to `PATH` to use it |
> | `bench-webui-*.jar` | Spring Boot fat jar |
> | `bench-webui.log`, `bench-webui.err` | bench-webui's stdout / stderr |
> | `bench-webui.pid` | PID file consumed by `stop-bench-tools` |
>
> The repo itself is untouched; the install dir is fully disposable —
> `rm -rf ~/ai-bench` (after stopping the service) is the clean
> uninstall.

Stop the web UI with the matching companion script:

```bash
./scripts/stop-bench-tools.sh         # macOS / Linux
.\scripts\stop-bench-tools.ps1        # Windows (PowerShell)
```

### Build from source (only if you're modifying the bench tools)

Run the **pre-build health check** first on a fresh machine —
particularly behind a corporate proxy. It is organised into named,
independently-runnable layers (JDK → proxy → TLS → trust-store →
mirror → wrapper → end-to-end) so every element of enterprise
connectivity is incrementally verified, with a per-layer pass/fail
verdict and a final summary table.

```bash
./scripts/build-health-check.sh                                  # macOS / Linux
.\scripts\build-health-check.ps1                                  # Windows
```

Useful flags: `--list-layers`, `--only tls,trust-store`,
`--from wrapper`, `--non-interactive`. See
[scripts/README.md](scripts/README.md) for the layer catalog and
[docs/enterprise-builder.md](docs/enterprise-builder.md) for
proxy / Artifactory / TLS troubleshooting.

Once the health check is green, build everything in dependency order:

```bash
./scripts/build-source.sh                                         # macOS / Linux
.\scripts\build-source.ps1                                        # Windows
```

To build sub-projects one at a time (or run `clean`, `test`, etc.):

```bash
cd banking-app    && ./gradlew build       # target app under evaluation
cd ../bench-harness && ./gradlew build      # evaluation libraries
cd ../bench-cli    && ./gradlew build       # CLI driver
cd ../bench-webui  && ./gradlew build       # management UI (port 7777)
```

The same flow works on Windows by substituting `.\gradlew.bat`.

### Common tasks

| Task | macOS / Linux | Windows |
|---|---|---|
| Build everything | `./scripts/build-source.sh` | `.\scripts\build-source.ps1` |
| Clean rebuild | `./scripts/build-source.sh -- clean build` | `.\scripts\build-source.ps1 -- clean build` |
| Run tests only | `./scripts/build-source.sh -- test` | `.\scripts\build-source.ps1 -- test` |
| Smoke test (subset) | `./scripts/smoke-test.sh` | n/a — use WSL or run subprojects directly |
| Start banking app with AppMap | `./gradlew -PjvmArgs="-javaagent:/path/to/appmap.jar" :app-bootstrap:bootRun` | `.\gradlew.bat -PjvmArgs="-javaagent:C:\path\to\appmap.jar" :app-bootstrap:bootRun` |
| Start bench-webui in dev mode | `cd bench-webui && ./gradlew bootRun` | `cd bench-webui; .\gradlew.bat bootRun` |

## Syncing updates

`main` is maintained as single-commit history with periodic force-push
rewrites, so a plain `git pull` will not work. The outer repo
(`ai-bench-java/`) and the **separate** nested `banking-app/` repo (with
its own `main` plus 24 `bug/BUG-XXXX/{break,fix}` branches) both need to
sync. Procedure is platform-specific but mechanical:

```bash
# macOS / Linux (from the ai-bench-java/ root)
git fetch --all --prune
git checkout main && git reset --hard origin/main
git rm --cached -r . 2>/dev/null && git reset --hard

cd banking-app
git fetch --all --prune
git checkout main && git reset --hard origin/main
git rm --cached -r . 2>/dev/null && git reset --hard
git branch --list 'bug/*' | sed 's/^[* ]*//' | xargs -I{} git branch -f {} origin/{}
cd ..
```

```powershell
# Windows (PowerShell, from the ai-bench-java\ root)
git fetch --all --prune
git checkout main; git reset --hard origin/main
git rm --cached -r . 2>$null; git reset --hard

cd banking-app
git fetch --all --prune
git checkout main; git reset --hard origin/main
git rm --cached -r . 2>$null; git reset --hard
git branch --list "bug/*" | % { $b = $_.Trim('* ').Trim(); git branch -f $b "origin/$b" }
cd ..
```

The `rm --cached -r .` + `reset --hard` step re-stages every file
through the repo's `.gitattributes` rules. This is what clears the
Windows CRLF mismatch that otherwise shows thousands of "modified"
files. The branch-reset loop in `banking-app/` resets each local bug
branch to its rewritten remote tip.

If sync hits an unresolvable state, **nuke and re-clone** always works:

```bash
rm -rf ai-bench-java
git clone https://github.com/alfredsisley10/ai-bench-java.git
git clone https://github.com/alfredsisley10/omnibank-demo.git ai-bench-java/banking-app
```

## Behind a corporate proxy

If a build hits one of:

- `Could not resolve all artifacts for configuration ':classpath'`
- `Could not GET 'https://repo.maven.apache.org/maven2/...'`
- `PKIX path building failed: unable to find valid certification path`
- `Connection refused` / `Connect timed out`

…run `scripts/build-health-check.{sh,ps1}` first — it diagnoses every
common corporate-network blocker (proxy not configured, TLS interception
by a corporate CA, missing `JAVA_HOME`, Maven Central blocked behind an
internal Artifactory mirror) and prints the exact remediation.

The full walkthrough — including config snippets for proxy auth,
Windows-ROOT / macOS Keychain trust-store wiring, and Gradle init
scripts that rewrite `mavenCentral()` to a corporate Artifactory
mirror — is in [docs/enterprise-builder.md](docs/enterprise-builder.md).

## LLM access

Two enterprise-friendly adapters are built in:

- **GitHub Copilot** — via a companion VSCode extension that exposes the
  `vscode.lm` Language Model API to the harness over a local socket.
  Lets the user reuse their existing Copilot entitlement.
- **Corporate OpenAI-spec API** — HTTPS client that performs Apigee
  OAuth2 token exchange, caches the token, and injects
  corporate-mandated custom headers (correlation-id, client-id, env,
  etc.) on every request. Drop-in replacement for the `openai` SDK
  against a bank's gateway.

See [docs/llm-integrations.md](docs/llm-integrations.md).

## Docs

- [Architecture](docs/architecture.md) — banking-app module map + harness component diagram
- [Bug catalog format](docs/bug-catalog.md) — the BUG-xxxx.yaml schema
- [Evaluation protocol](docs/eval-protocol.md) — how runs are driven, scored, and reported
- [LLM integrations](docs/llm-integrations.md) — Copilot adapter + corporate gateway adapter
- [Enterprise builder](docs/enterprise-builder.md) — proxy + Artifactory + TLS handling
- [AppMap integration](docs/appmap-integration.md) — trace collection and injection
- [Operations guide](docs/operations-guide.md) — running, monitoring, and troubleshooting

[gh-releases]: https://github.com/alfredsisley10/ai-bench-java/releases
