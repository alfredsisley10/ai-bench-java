# Scripts

Ops helpers used by the harness and by humans.

## build-health-check (PowerShell + bash)

Pre-build sanity check for a fresh machine, organised into **named,
independently-runnable LAYERS** so every element of enterprise
connectivity is incrementally tested and verified — JDK, proxy
(authenticated or not), corporate Maven mirror (with or without
auth, optionally also behind the proxy), TLS chain, OS-integrated
trust store, Gradle wrapper distribution, end-to-end `gradlew --version`.

Every layer prints its own per-layer pass/fail/warn line (e.g.
`[layer:tls WARN]`) so you can see at a glance which step in the
build chain is the blocker, and a final per-layer summary table is
printed at the end of the run.

```powershell
# Windows (PowerShell)
.\scripts\build-health-check.ps1
```

```bash
# macOS / Linux
./scripts/build-health-check.sh
```

Both scripts run the same set of layers (slug-for-slug), so CI or
onboarding docs can call whichever matches the platform without
behavioral differences. Run this before the first `gradlew build` on any
new machine — especially behind a corporate proxy. See
[docs/enterprise-builder.md](../docs/enterprise-builder.md) for fixing
the failures it surfaces.

### Layer model

Run `--list-layers` (`-ListLayers` in PowerShell) to print every layer
the script knows about, in order:

```bash
./scripts/build-health-check.sh --list-layers
```

```
jdk                 JDK 17-25 + javac/keytool present
disk                Free disk for Gradle caches
ps-policy           PowerShell execution policy (Windows-only)
proxy               Proxy configuration (env, gradle.properties, VSCode sync)
gradle-props        gradle.properties consistency
maven-config        Corporate settings.xml / maven-wrapper.properties discovery
mirror-setup        Artifactory external-mirror proactive setup
repo-reach          Artifact repository reachability
mirror-remediation  Maven-Central mirror remediation when direct egress fails
tls                 TLS chain inspection (corporate-CA detection)
trust-store         OS-integrated trust-store wiring (Windows-ROOT, KeychainStore, /etc/ssl)
jvm-http-auth       JVM HTTP-tunnel auth schemes (Basic/NTLM over CONNECT)
wrapper             Gradle wrapper distribution + jar sanity
e2e                 End-to-end: gradlew --version
```

### Running a subset of layers

```bash
# Only the trust-store + TLS layers
./scripts/build-health-check.sh --only tls,trust-store --non-interactive

# Start at the wrapper layer (assume everything before it is healthy)
./scripts/build-health-check.sh --from wrapper --non-interactive

# Run everything except the slow end-to-end probe
./scripts/build-health-check.sh --skip e2e --non-interactive
```

```powershell
# PowerShell (Windows)
.\scripts\build-health-check.ps1 -Only tls,trust-store -NonInteractive
.\scripts\build-health-check.ps1 -From wrapper -NonInteractive
.\scripts\build-health-check.ps1 -Skip e2e -NonInteractive
```

### Enterprise connectivity coverage

Each enterprise concern lives behind a dedicated layer so it can be
incrementally tested:

| Concern | Layer(s) |
|---|---|
| Authenticated proxy | `proxy`, `gradle-props`, `jvm-http-auth` |
| Unauthenticated proxy | `proxy`, `gradle-props` |
| Corporate Artifactory mirror (no auth) | `maven-config`, `mirror-setup`, `repo-reach`, `mirror-remediation` |
| Mirror with HTTP Basic auth | as above + `gradle-props` (credential pairs) |
| Mirror with Bearer / Identity Token | as above + `repo-reach` (auto-detects Bearer requirement and rewrites the init script with `HttpHeaderAuthentication`) |
| Mirror behind authenticated proxy | all of the above; reachability probes route through the proxy that's configured in `gradle.properties` |
| Corporate-CA TLS interception | `tls` (raw `openssl s_client` chain), `trust-store` (wires JDK to the OS trust store) |
| OS-integrated trust store | `trust-store` — Windows-ROOT on Windows, KeychainStore on macOS, distro CA bundle import on Linux |
| Gradle wrapper distribution | `wrapper` (download, SHA-256, jar size sanity) |

## start-bench-tools / stop-bench-tools

One-command boot of the pre-built `bench-cli` and `bench-webui`. Copies
artifacts out of the repo's `dist/` directory (or downloads them from
the GitHub Releases API if `dist/` is empty), unzips `bench-cli`, and
launches `bench-webui` in the background on http://localhost:7777.

```bash
./scripts/start-bench-tools.sh        # macOS / Linux
.\scripts\start-bench-tools.ps1        # Windows (PowerShell)
```

`stop-bench-tools.{sh,ps1}` reads the PID file the start script writes,
sends a graceful shutdown, and escalates to a force-kill after 15 s. A
missing PID file is reported as "nothing to stop" — re-running is safe.

Override the install root with `INSTALL_DIR=…` (bash) or
`-InstallDir …` (PowerShell). Default is `~/ai-bench`.

## build-source

Build every sub-project from source in dependency order. Replaces the
manual `cd X && ./gradlew build` sequence:

```bash
./scripts/build-source.sh                       # all sub-projects, default `build`
./scripts/build-source.sh -- clean test         # pass extra args to Gradle
./scripts/build-source.sh --skip=banking-app    # skip a sub-project
```

```powershell
.\scripts\build-source.ps1
.\scripts\build-source.ps1 -- clean test
.\scripts\build-source.ps1 -Skip banking-app
```

## smoke-test.sh

End-to-end sanity check: compiles the key modules and runs a small
subset of tests. Intended to fail loudly on first checkout when
anything is broken.

```bash
./scripts/smoke-test.sh
```

Requires a JDK 17–25 on `PATH`. Each sub-project ships its own Gradle
9.4.x wrapper so no separate Gradle install is needed.

For a full build use `./scripts/build-source.sh`.
