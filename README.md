# ai-bench-java

Internal SWE-Bench-style benchmark platform for evaluating AI coding assistant **effectiveness** and **efficiency** on enterprise-scale Java applications — with particular focus on measuring the impact of [AppMap](https://appmap.io) runtime traces as solver context.

## Why this exists

Public SWE-Bench relies on open-source repositories that mainstream LLMs have been trained on. Results are contaminated: models may have memorized fix patches rather than reasoned about them. This project addresses that with a **dual strategy**:

1. **Bespoke baseline** — a bespoke large-scale Java banking application (**Omnibank**) modeling consumer + corporate banking. Models are assured not to have been trained on it. Bugs are intentionally injected along the commit timeline, with oracle fix commits and hidden unit tests. Clean signal.

2. **Real-world harness** — the same evaluation machinery can be pointed at actual enterprise GitHub repositories the user has access to. Scans repos the user can see, identifies good benchmark candidates, pulls JIRA tickets as problem statements, builds behind corporate proxy/Artifactory, scores solvers. Transitions evaluation from a synthetic baseline to the actual codebases that matter.

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
├── docs/              Architecture, protocols, integration guides
└── scripts/           Build and run helpers
```

## Quick start

### Prerequisites

- **JDK 17 through 25** on `PATH` (`java -version` prints 17.x / 21.x / 25.x, etc). OpenJDK, Eclipse Temurin, Oracle JDK, Amazon Corretto, and Azul Zulu all work. Toolchain is pinned to 17 for source-level cross-compatibility; Gradle 9.4.1 can run as daemon on any JDK from 17 to 25, and the Foojay toolchain resolver (wired into every sub-project's `settings.gradle.kts`) will auto-download a JDK 17 if the user's default is a different major version.
- **Git** for checkout.
- No separate Gradle install needed — each sub-project ships its own wrapper.

### Install pre-built bench tools (recommended for most users)

The benchmark tooling — `bench-cli` and `bench-webui` — is published
both as a [GitHub Release](https://github.com/alfredsisley10/ai-bench-java/releases)
**and** committed to `dist/` in this repo, so you do **not** need to
build them locally. The `banking-app` is intentionally **not** prebuilt:
it's the thing under evaluation, and the harness has to compile and
test it locally to measure the metrics that matter.

> **Enterprise note.** Because the artifacts are also stored under
> `dist/`, a plain `git clone` (or `git fetch` of an existing checkout)
> is sufficient — no GitHub-Releases access required. The startup
> script below copies them out of `dist/` automatically when present.

**One-command install + start.** From a clone of this repo, run:

```bash
# macOS / Linux
./scripts/start-bench-tools.sh
```

```powershell
# Windows (PowerShell)
.\scripts\start-bench-tools.ps1
```

Each script: verifies a JDK 17–25 is on `PATH`; either copies pre-built
artifacts from the repo's `dist/` (if present) or downloads them from
the GitHub Releases API (built-in `Invoke-WebRequest` / `curl` — no
`gh` CLI required) into `~/ai-bench` (override with `INSTALL_DIR=…` /
`-InstallDir`); unzips `bench-cli`; launches `bench-webui` in the
background; and prints the `bench-cli` launcher path plus a one-liner
to add it to `PATH`. Re-running detects an already-running `bench-webui`
and exits without launching a duplicate.

Stop the web UI with the matching companion script:

```bash
# macOS / Linux
./scripts/stop-bench-tools.sh
```

```powershell
# Windows (PowerShell)
.\scripts\stop-bench-tools.ps1
```

The stop script reads the same PID file the start script writes,
sends a graceful shutdown, waits up to 15 s, and escalates to a
force-kill if the process refuses to exit. A missing/stale PID file
is reported as "nothing to stop" — re-running is always safe.

**Manual install** if you'd rather skip the script. From a clone of the
repo:

```bash
# macOS / Linux
mkdir -p ~/ai-bench
cp dist/bench-cli-*.zip dist/bench-webui-*.jar ~/ai-bench/
cd ~/ai-bench && unzip -q bench-cli-*.zip
./bench-cli-*/bin/bench-cli --help               # CLI entry point
java -jar bench-webui-*.jar                       # web UI on http://localhost:7777
```

```powershell
# Windows (PowerShell)
New-Item -ItemType Directory -Force -Path $HOME\ai-bench | Out-Null
Copy-Item .\dist\bench-cli-*.zip,.\dist\bench-webui-*.jar $HOME\ai-bench\
Set-Location $HOME\ai-bench
Expand-Archive -Force -Path .\bench-cli-*.zip -DestinationPath .
& .\bench-cli-0.1.0-SNAPSHOT\bin\bench-cli.bat --help
java -jar .\bench-webui-0.1.0-SNAPSHOT.jar         # web UI on http://localhost:7777
```

Only requires JDK 17–25 on `PATH` — no Gradle, no internet egress to
Maven Central, no source build. The same artifacts run on macOS, Linux,
and Windows.

If you need to **modify** the bench tools (or build the `banking-app`
target), use the source build below.

### Pre-build health check (recommended on a fresh machine)

Before the first build — especially on Windows behind a corporate proxy —
run the bundled health check. It verifies JDK version, proxy
configuration, reachability to every artifact repo Gradle hits at build
time, TLS chain (detects corporate-CA interception), and the Gradle
distribution download. Exits non-zero with a remediation hint per
failure.

```powershell
# Windows (PowerShell)
.\scripts\build-health-check.ps1
```

```bash
# macOS / Linux
./scripts/build-health-check.sh
```

If everything is green, jump straight to **Build + run** below. If the
check flags blockers — proxy missing, TLS chain ending at a non-public
root, JDK wrong version — see **Behind a corporate proxy** at the end of
this section.

### Build + run (macOS / Linux)

```bash
# Build the target banking app (green on first checkout = baseline)
cd banking-app && ./gradlew build

# Build the harness
cd ../bench-harness && ./gradlew build

# Run the CLI
../bench-cli/bin/bench --help

# Launch the management UI (http://localhost:7777)
../bench-webui/bin/bench-webui
```

### Build + run (Windows)

Use PowerShell or `cmd.exe` — the wrapper uses the `.bat` variant with an
explicit `.\` prefix (PowerShell requires it; `cmd.exe` also accepts it).
Paths use backslashes.

```powershell
# Build the target banking app (green on first checkout = baseline)
cd banking-app
.\gradlew.bat build

# Build the harness
cd ..\bench-harness
.\gradlew.bat build

# Run the CLI
..\bench-cli\bin\bench.bat --help

# Launch the management UI (http://localhost:7777)
..\bench-webui\bin\bench-webui.bat
```

### Common tasks

| Task | macOS / Linux | Windows |
|---|---|---|
| Clean build | `./gradlew clean build` | `.\gradlew.bat clean build` |
| Run tests only | `./gradlew test` | `.\gradlew.bat test` |
| Start banking app with AppMap agent | `./gradlew -PjvmArgs="-javaagent:/path/to/appmap.jar" :app-bootstrap:bootRun` | `.\gradlew.bat -PjvmArgs="-javaagent:C:\path\to\appmap.jar" :app-bootstrap:bootRun` |
| Start bench-webui in dev mode | `cd bench-webui && ./gradlew bootRun` | `cd bench-webui; .\gradlew.bat bootRun` |

### Syncing updates

Because we maintain a single-commit history on `main` and force-push
rewrites, a plain `git pull` will not work. Both repos live side by
side in the same directory layout — `ai-bench-java/` is the outer repo,
and `ai-bench-java/banking-app/` is a **separate** (nested) repo with
its own `main` plus 24 `bug/BUG-XXXX/{break,fix}` branches. Every sync
needs to touch both.

**Windows (PowerShell, from the `ai-bench-java\` root):**

```powershell
git fetch --all --prune
git checkout main
git reset --hard origin/main
git rm --cached -r . 2>$null
git reset --hard

cd banking-app
git fetch --all --prune
git checkout main
git reset --hard origin/main
git rm --cached -r . 2>$null
git reset --hard
git branch --list "bug/*" | % { $b = $_.Trim('* ').Trim(); git branch -f $b "origin/$b" }
cd ..
```

**macOS / Linux (bash, from the `ai-bench-java/` root):**

```bash
git fetch --all --prune
git checkout main
git reset --hard origin/main
git rm --cached -r . 2>/dev/null
git reset --hard

cd banking-app
git fetch --all --prune
git checkout main
git reset --hard origin/main
git rm --cached -r . 2>/dev/null
git reset --hard
git branch --list 'bug/*' | sed 's/^[* ]*//' | xargs -I{} git branch -f {} origin/{}
cd ..
```

Why each step:
- `fetch --all --prune` — picks up rewritten history on both remotes and
  drops stale branches.
- `reset --hard origin/main` — aligns local `main` with the force-pushed
  remote (force-push-safe; any local changes on `main` are discarded).
- `rm --cached -r .` + `reset --hard` — re-stages every file through the
  repo's `.gitattributes` rules. This is what clears the Windows CRLF
  mismatch that otherwise shows thousands of "modified" files.
- The `branch -f … origin/…` loop in `banking-app/` resets each local
  bug branch to its rewritten remote tip.

After syncing, run the health check before the first build:

```powershell
.\scripts\build-health-check.ps1    # Windows
./scripts/build-health-check.sh     # macOS / Linux
```

**Fallback — nuke and re-clone** — always works, useful if the sync
hits an unresolvable state:

```powershell
cd <parent-dir>
Remove-Item -Recurse -Force ai-bench-java
git clone https://github.com/alfredsisley10/ai-bench-java.git
cd ai-bench-java
git clone https://github.com/alfredsisley10/omnibank-demo.git banking-app
```

### Behind a corporate proxy

If `.\gradlew.bat build` (or `./gradlew build`) fails with one of:

- `Could not resolve all artifacts for configuration ':classpath'`
- `Could not GET 'https://repo.maven.apache.org/maven2/...'`
- `PKIX path building failed: unable to find valid certification path`
- `Connection refused` / `Connect timed out`

…you're hitting one of three corporate-network classics. Run
`scripts\build-health-check.ps1` first — it will tell you which one.

#### 1. Proxy not configured

Gradle does **not** auto-detect Windows / macOS system-proxy settings.
Configure it explicitly in your **user-level** `gradle.properties` so
every project on the machine inherits the same settings:

- Windows: `%USERPROFILE%\.gradle\gradle.properties`
- macOS / Linux: `~/.gradle/gradle.properties`

```properties
# user-level gradle.properties — applies to every Gradle project
systemProp.https.proxyHost=proxy.corp.example.com
systemProp.https.proxyPort=8080
systemProp.https.nonProxyHosts=localhost|127.0.0.1|*.corp.example.com
systemProp.http.proxyHost=proxy.corp.example.com
systemProp.http.proxyPort=8080
systemProp.http.nonProxyHosts=localhost|127.0.0.1|*.corp.example.com
# If the proxy needs auth (Windows NTLM corp networks usually do):
# systemProp.https.proxyUser=YOUR_USER
# systemProp.https.proxyPassword=YOUR_PASSWORD
```

The same file is honored on macOS and Windows — only the path differs.

#### 2. Corporate TLS interception (`PKIX path building failed`)

If a corporate proxy intercepts HTTPS to scan content, it presents a
chain rooted in your org's internal CA. The JDK's bundled truststore
doesn't trust that root, so the TLS handshake fails. Import the
corporate root once into the JDK's `cacerts` (per JDK install):

```powershell
# Windows — adjust JAVA_HOME if needed; default password is `changeit`
keytool -importcert -alias corp-root `
        -file C:\path\to\corp-root.crt `
        -keystore "$env:JAVA_HOME\lib\security\cacerts" `
        -storepass changeit -noprompt
```

```bash
# macOS / Linux
sudo keytool -importcert -alias corp-root \
        -file /path/to/corp-root.crt \
        -keystore "$JAVA_HOME/lib/security/cacerts" \
        -storepass changeit -noprompt
```

Get the corporate root from your IT team, your browser's certificate
manager (export the issuer of any internal HTTPS site), or
`scripts\build-health-check.ps1` will tell you which root the proxy
is presenting.

#### 3. Wrong JDK or `JAVA_HOME` not set

`gradlew.bat` resolves Java by checking `JAVA_HOME` first, then `PATH`.
A common Windows failure mode is `JAVA_HOME` pointing at JDK 17 (or 11)
while `java.exe` on `PATH` is 21 — the wrapper picks `JAVA_HOME` and
fails. Set `JAVA_HOME` to a JDK 17+ install root in System →
Environment Variables, or temporarily in the current shell:

```powershell
# Windows (PowerShell, current shell only)
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17"
```

```bash
# macOS — pin to the 21 install via java_home helper
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
```

After fixing whichever blocker the health check flagged, re-run
`build-health-check` to confirm green, then re-attempt the build.

#### 4. Maven Central blocked (need an internal mirror)

Corporate networks often block direct egress to `repo.maven.apache.org`
and expect builds to resolve through an internal Artifactory / Nexus
mirror. If Maven Central fails in the health check, Gradle can't
download Spring Boot / Kotlin stdlib / JUnit / Jackson — nothing will
build.

The fix is a user-wide Gradle init script that rewrites every
`mavenCentral()` call to the corporate URL. The health check will
offer to write it for you if it can find the URL automatically; if not,
you'll need to supply it.

**Where to find your org's Maven mirror URL (JFrog Artifactory):**

1. Log in to your corporate Artifactory (ask IT or your platform team
   for the URL if you don't already have it bookmarked).
2. In the upper-right corner of the web UI, click the **avatar icon**
   (your profile).
3. Select **"Set Me Up"**.
4. Select **Maven**, then follow the on-screen workflow. Artifactory
   walks you through selecting the right virtual / remote repository
   and displays the repo URL — typically
   `https://artifactory.<corp>.com/artifactory/<repo-name>/` — along
   with any credential snippet you can paste into
   `~/.gradle/gradle.properties` verbatim (Gradle honors the same
   Artifactory Maven repo; no need to pick a Gradle-specific one).

The health check will also auto-detect existing corporate Maven config
in every place IT is likely to have dropped it, and offer to reuse the
URL:

- `settings.xml` — the `<mirror>` URL **or**, if the file has no
  `<mirror>` block, every `<repository>` / `<pluginRepository>` `<url>`
  inside a `<profile>` (the pattern JFrog Artifactory's "Set Me Up →
  Maven" workflow generates; `<id>central</id>` on a repo overrides
  Maven Central by default-id resolution). Checked in `~/.m2/`,
  **`~/Downloads/`** (where the "Set Me Up" flow typically saves it),
  and the current project root.
- `maven-wrapper.properties` — checked in `~/.m2/wrapper/`,
  `~/.mvn/wrapper/`, `~/Downloads/`, the current project root, and the
  project's `.mvn/wrapper/` subdirectory

If either is found, the health check presents the URL as a numbered
option so you don't have to re-type it.

**Pointing the scan at a non-standard location:**

```powershell
# Windows -- direct file
.\scripts\build-health-check.ps1 -MavenConfigPath C:\custom\settings.xml

# Windows -- a directory to recursively search (up to depth 3)
.\scripts\build-health-check.ps1 -MavenConfigPath C:\corp-tools\
```

```bash
# macOS / Linux
./scripts/build-health-check.sh --maven-config /path/to/settings.xml
./scripts/build-health-check.sh --maven-config ~/corp-tools/
```

**Manual init-script template** (in case the health check prompt is
skipped): write this to `~/.gradle/init.d/corp-repos.gradle.kts`:

```kotlin
val corpMavenUrl = "https://artifactory.<corp>.com/artifactory/maven-central/"

fun maybeRewrite(repo: ArtifactRepository) {
    if (repo is MavenArtifactRepository) {
        val u = repo.url.toString()
        if (u.contains("repo.maven.apache.org") ||
            u.contains("plugins.gradle.org") ||
            u.contains("repo1.maven.org")) {
            repo.setUrl(corpMavenUrl)
        }
    }
}

settingsEvaluated {
    pluginManagement.repositories.configureEach { maybeRewrite(this) }
    dependencyResolutionManagement.repositories.configureEach { maybeRewrite(this) }
}

allprojects {
    buildscript.repositories.configureEach { maybeRewrite(this) }
    repositories.configureEach { maybeRewrite(this) }
}
```

Covers all four resolution scopes Gradle 9.x uses: settings-time
plugin management, centralized dependency management, per-project
buildscripts, and project dependencies. Without the
`settingsEvaluated` hook, plugins declared via
`plugins { id("...") version "..." }` miss the rewrite and hit
`repo.maven.apache.org` directly — the same failure mode that
produces `Could not resolve all artifacts for configuration
':classpath'` errors on plugin transitive deps like
`com.google.code.gson`.

If the repo requires credentials, add them to `~/.gradle/gradle.properties`:

```properties
orgInternalMavenUser=<username>
orgInternalMavenPassword=<access-token-or-api-key>
```

…and extend each `all { … }` block with a `credentials { username = providers.gradleProperty("orgInternalMavenUser").get(); password = providers.gradleProperty("orgInternalMavenPassword").get() }` clause.

## LLM access

Two enterprise-friendly adapters are built in:

- **GitHub Copilot** — via a companion VSCode extension that exposes the `vscode.lm` Language Model API to the harness over a local socket. Lets the user reuse their existing Copilot entitlement.
- **Corporate OpenAI-spec API** — HTTPS client that performs Apigee OAuth2 token exchange, caches the token, and injects corporate-mandated custom headers (correlation-id, client-id, env, etc.) on every request. Drop-in replacement for `openai` SDK against a bank's gateway.

See [docs/llm-integrations.md](docs/llm-integrations.md).

## Docs

- [Architecture](docs/architecture.md) — banking-app module map + harness component diagram
- [Bug catalog format](docs/bug-catalog.md) — the BUG-xxxx.yaml schema
- [Evaluation protocol](docs/eval-protocol.md) — how runs are driven, scored, and reported
- [LLM integrations](docs/llm-integrations.md) — Copilot adapter + corporate gateway adapter
- [Enterprise builder](docs/enterprise-builder.md) — proxy + Artifactory handling
- [AppMap integration](docs/appmap-integration.md) — trace collection and injection

## Status

Active initial build. Banking-app modules in flight; harness scaffolding behind.
