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
new machine — especially behind a corporate proxy. See the main
README's "Behind a corporate proxy" section for fixing the failures
it surfaces.

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

## smoke-test.sh

End-to-end sanity check: compiles key modules and runs a small subset of tests. Intended to fail loudly on first checkout if anything is broken.

```bash
./scripts/smoke-test.sh
```

**Prerequisites on this machine:**
- Java 21 (the project's toolchain target)
- Gradle 8.10+ OR a populated Gradle wrapper in each sub-project (run `gradle wrapper --gradle-version 8.10.2` once per subproject)

The smoke test intentionally exercises only a few targets. For a full build:

```bash
cd banking-app && ./gradlew build
cd ../bench-harness && ./gradlew build
cd ../bench-cli && ./gradlew build
cd ../bench-webui && ./gradlew build
```
