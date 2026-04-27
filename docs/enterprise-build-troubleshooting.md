# Enterprise build troubleshooting

This runbook catalogs failure modes when running `ai-bench-java` builds behind
a corporate proxy + Artifactory mirror. Every entry below was reproduced
against the local `enterprise-sim` simulator (`tooling/enterprise-sim/`) — the
"observed error" snippets are real captured Gradle output, not synthesized.

## Reproducing locally

```powershell
# Terminal 1 — start the simulator (keeps running)
java tooling/enterprise-sim/src/EnterpriseSim.java

# Terminal 2 — run the canary or scenario set
.\tooling\enterprise-sim\scripts\run-canary.ps1
.\tooling\enterprise-sim\scripts\run-scenarios.ps1            # all scenarios
.\tooling\enterprise-sim\scripts\run-scenarios.ps1 -Scenario 01-mirror-down
```

Per-scenario stdout/stderr + a snapshot of the proxy/mirror audit logs land in
`enterprise-sim-logs/scenarios/<scenario-name>/`.

## How configuration is supplied (read-only on system properties)

The build reads system properties set by the invoker; it never writes them.
Settings travel with the repo via `banking-app/settings.gradle.kts` and
`banking-app/build.gradle.kts`, which inspect `System.getProperty(...)` and
log a startup banner showing what they detected.

The canonical correct invocation:

```powershell
./gradlew.bat `
    -Dhttps.proxyHost=localhost -Dhttps.proxyPort=3128 `
    -Dhttps.proxyUser=bench-user -Dhttps.proxyPassword=bench-pass `
    -Dhttp.proxyHost=localhost  -Dhttp.proxyPort=3128 `
    -Dhttp.proxyUser=bench-user  -Dhttp.proxyPassword=bench-pass `
    "-Dhttp.nonProxyHosts=localhost|127.0.0.1" `
    -Djdk.http.auth.tunneling.disabledSchemes= `
    -Denterprise.sim.mirror=http://localhost:8081 `
    :shared-domain:compileJava
```

`-Djdk.http.auth.tunneling.disabledSchemes=` is required: JDK 17 disables
HTTP Basic on HTTPS-via-proxy `CONNECT` by default. Setting the property to
empty re-enables it.

---

## Failure scenarios

### 01 — Mirror unreachable / 5xx

**Setup:** sim launched with `--mirror-down` (all responses are 503).

**Observed error** (`stderr.txt`):

```
FAILURE: Build failed with an exception.

* Where:
Settings file '...banking-app\settings.gradle.kts' line: 48

* What went wrong:
Plugin [id: 'org.gradle.toolchains.foojay-resolver-convention', version: '0.8.0']
was not found in any of the following sources:

- Gradle Core Plugins (plugin is not in 'org.gradle' namespace)
- Included Builds (No included builds contain this plugin)
- Plugin Repositories (could not resolve plugin artifact
  'org.gradle.toolchains.foojay-resolver-convention:...:0.8.0')
  Searched in the following repositories:
    maven(http://localhost:8081/gradle-plugins/)
```

**Diagnostic:** failure happens at *plugin resolution* (settings phase,
line 48 = the `pluginManagement { repositories { maven(...) } }` block). The
`Searched in the following repositories` list shows the only place Gradle
looked. Confirm in the proxy/mirror log that 5xx are being returned:

```bash
grep '"status":5' enterprise-sim-logs/mirror.log
```

**Fix workflow:**
1. `curl -i http://localhost:8081/health` — sim should return `200 ok`.
   - 503 / connection refused → restart `enterprise-sim`.
2. If sim is healthy but returns 5xx for real Maven Central paths, check
   `enterprise-sim-logs/sim.stderr` for upstream errors.
3. If upstream Maven Central itself is down, retry later or add a fallback
   repository to `pluginManagement.repositories`.

---

### 02 — Wrong proxy credentials *(silent — see note below)*

**Setup:** build sends `-Dhttps.proxyPassword=NOPE`.

**Observed behavior:** build proceeds normally and fails on the
unrelated `Result.java` compile bug. **No proxy traffic at all** in
`proxy.log`.

**Why it didn't fail loudly:** with the JDK 17 toolchain already cached at
`~/.gradle/jdks/` and most plugins cached at `~/.gradle/caches/modules-2/`,
nothing actually goes over the proxy in this build. The wrong password is
never tested.

**Diagnostic:** to surface this, force fresh resolution AND a Foojay round-trip:

```powershell
Remove-Item -Recurse -Force ~/.gradle/jdks
Remove-Item -Recurse -Force ~/.gradle/caches/modules-2/metadata-2*
.\tooling\enterprise-sim\scripts\run-scenarios.ps1 -Scenario 02-wrong-proxy-password
```

When wrong creds *do* hit the wire, the captured error is:

```
Unable to tunnel through proxy. Proxy returns "HTTP/1.1 407 Proxy Authentication Required"
```

**Fix workflow:**
1. Verify `enterprise-sim-logs/proxy.log` shows `"authOk":false,"status":407`
   for the destination that failed.
2. Confirm credentials match the sim's `--user` / `--pass` flags
   (defaults: `bench-user` / `bench-pass`).
3. Re-run with corrected `-Dhttps.proxyPassword=...`.

**Risk for CI/CD rollouts:** because correctness is mostly invisible behind
a warm cache, a misconfig may not surface until a fresh CI agent provisions.
Recommended: every CI agent runs the canary script post-bootstrap as a
smoke test.

---

### 03 — Forgot `-Denterprise.sim.mirror` *(silent traffic leak)*

**Setup:** build invocation omits `-Denterprise.sim.mirror=...` but keeps
the proxy settings.

**Observed behavior:** build succeeds (modulo the unrelated `Result.java`
issue). Proxy log is busy — 77 CONNECTs to **upstream** hosts:

```
"dest":"plugins-artifacts.gradle.org:443"
"dest":"plugins.gradle.org:443"
"dest":"repo.maven.apache.org:443"
```

**Diagnostic:** without the mirror sysprop, `settings.gradle.kts` falls back
to `gradlePluginPortal()` + `mavenCentral()`. The proxy *can* tunnel to those
public hosts (because it has no allow-list), so the build works — slowly,
and in violation of the "use Artifactory mirror" policy. Catch this in audit
review:

```bash
# Anything outside the allow-list is a leak
grep -oE '"dest":"[^"]+"' enterprise-sim-logs/proxy.log | sort -u
# allow-list: api.foojay.io:443, services.gradle.org:443,
#             github.com:443, release-assets.githubusercontent.com:443
```

**Fix workflow:** always pass `-Denterprise.sim.mirror=http://localhost:8081`
when running enterprise-mode builds. Or commit the value to
`banking-app/gradle.properties` as `systemProp.enterprise.sim.mirror=...` if
the project should default to mirror-only.

---

### 04 — Typo'd `http.nonProxyHosts` *(silent on this stack)*

**Setup:** invocation passes `-Dhttp.noProxyHosts=...` (missing the `n` in `non`).

**Observed behavior:** build succeeds normally. 391 mirror requests, 0 proxy
requests for `localhost`.

**Why it didn't fail:** Gradle's HTTP client appears to bypass the proxy for
`localhost` regardless of the `nonProxyHosts` value — so the typo is masked.
On a different stack (e.g., raw `java.net.URLConnection`-based tooling), the
typo *would* cause localhost traffic to hit the proxy.

**Diagnostic:** in suspicious environments, check that the mirror endpoint
appears in `mirror.log` and **not** in `proxy.log`:

```bash
grep -c "" enterprise-sim-logs/mirror.log    # should be > 0
grep '"dest":"localhost' enterprise-sim-logs/proxy.log    # should be empty
```

If localhost shows up in proxy.log, the `nonProxyHosts` setting is being
ignored. Common typos to watch for:

| Wrong | Right |
|---|---|
| `noProxyHosts` | `nonProxyHosts` |
| `http.nonproxyHosts` (lowercase p) | `http.nonProxyHosts` |
| comma separator (`localhost,127.0.0.1`) | pipe (`localhost\|127.0.0.1`) |

**Fix workflow:** correct the property name and verify with the diagnostic
above.

---

### 05 — Foojay blocked at proxy *(silent when JDK is cached)*

**Setup:** sim launched with `--proxy-blackhole=api.foojay.io`.

**Observed behavior on a warm cache:** build succeeds. JDK 17 was already at
`~/.gradle/jdks/jdk-17.0.18.8-hotspot/`, so Foojay never fired.

**Observed error on a cold cache** (`Remove-Item -Recurse -Force ~/.gradle/jdks`
first):

```
> Could not resolve all task dependencies of task ':shared-domain:compileJava'.
   > Could not download foojay-disco metadata from https://api.foojay.io/...
     Reason: 502 Bad Gateway
```

**Fix workflow:**
1. **Preferred** — make Gradle prefer the system JDK so Foojay isn't needed:
   add to `banking-app/gradle.properties`:
   ```
   org.gradle.java.installations.fromEnv=JAVA_HOME
   org.gradle.java.installations.auto-download=false
   ```
   The build will fail fast if no compatible local JDK is found, with a clear
   message naming the version it needed.
2. **Alternate** — allow Foojay through the corporate proxy (request from
   network team).

This scenario also explains the **138 MB JDK download** in the canary's proxy
log: even with a system JDK 17 installed, Foojay re-fetched its own copy
because `auto-detect` doesn't consider system installs by default in some
configurations.

---

### 06 — Mirror missing a required plugin (artifact 404)

**Setup:** sim launched with `--mirror-404=.*spring-boot.*`.

**Observed error** (`stderr.txt`):

```
FAILURE: Build failed with an exception.

* What went wrong:
A problem occurred configuring root project 'omnibank'.
> Could not resolve all artifacts for configuration 'classpath'.
   > Could not find org.springframework.boot:spring-boot-gradle-plugin:3.3.4.
     Searched in the following locations:
       - http://localhost:8081/gradle-plugins/org/springframework/boot/spring-boot-gradle-plugin/3.3.4/spring-boot-gradle-plugin-3.3.4.pom
     Required by:
         buildscript of root project 'omnibank' > org.springframework.boot:org.springframework.boot.gradle.plugin:3.3.4
```

**Diagnostic:** Gradle prints the exact URL it tried. `mirror.log` will show
matching `"status":404`:

```bash
grep '"status":404' enterprise-sim-logs/mirror.log | grep -oE '"path":"[^"]+"'
```

**Fix workflow:**
1. If the missing artifact is a real plugin you depend on: contact your
   Artifactory admin to verify the upstream sync includes `plugins.gradle.org/m2/`.
2. If the artifact is a transitive plugin marker (e.g., `*.gradle.plugin`),
   Gradle's plugin-resolution flow can fall back to the `dependencies`
   coordinate — these 404s on `*.gradle.plugin/*.jar` paths are *expected*
   and not failures. Filter them out of the audit:
   ```bash
   grep '"status":404' enterprise-sim-logs/mirror.log | grep -v '\.gradle\.plugin.*\.jar'
   ```

---

## Reading the audit logs

Both `enterprise-sim-logs/proxy.log` and `enterprise-sim-logs/mirror.log` are
JSON Lines (one JSON object per line). Sample summaries:

```bash
# Distinct proxy destinations (compare against allow-list)
grep -oE '"dest":"[^"]+"' enterprise-sim-logs/proxy.log | sort -u

# Mirror cache-hit ratio
hits=$(grep -c '"cacheHit":true' enterprise-sim-logs/mirror.log)
total=$(grep -c '' enterprise-sim-logs/mirror.log)
echo "$hits / $total"

# Authentication failures (407s)
grep '"status":407' enterprise-sim-logs/proxy.log
```

The canary script (`run-canary.ps1`) prints a summary block automatically.

## Allow-list policy

Anything appearing in `proxy.log` outside this list is a leak that should be
investigated:

| Host | Reason |
|---|---|
| `api.foojay.io:443` | JDK toolchain query |
| `github.com:443` | Foojay redirect target for JDK download |
| `release-assets.githubusercontent.com:443` | Adoptium JDK ZIP CDN |
| `services.gradle.org:443` | Wrapper / version probe |

For a stricter posture (no proxied traffic — mirror is full-fat Artifactory):
remove all four from the allow-list and configure the mirror to also serve
JDKs and the Gradle distribution.
