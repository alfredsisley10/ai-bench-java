# Enterprise builder

`ai-bench-java` is meant to run inside corporate networks. That means:

- HTTPS traffic must go through a forward proxy (often authenticated).
- Dependency resolution must be redirected from
  `repo.maven.apache.org` / `plugins.gradle.org` to an internal
  Artifactory / Nexus mirror.
- TLS to the mirror requires the corporate CA bundle.
- Some builds gate on corporate credentials (Artifactory token, NPM
  enterprise token).

This doc has two halves:

1. **End-user remediation** — what to do when `gradlew build` fails on
   a corporate machine. Run `scripts/build-health-check.{sh,ps1}` first;
   it diagnoses and offers fixes for everything below.
2. **Harness internals** — what `harness-builder` writes when it drives
   a benchmark build, so you can audit the overlay it produces.

---

## End-user remediation

Common error → root cause → fix:

| Error fragment | Likely cause | Fix |
|---|---|---|
| `Could not resolve all artifacts for configuration ':classpath'` | Gradle can't reach Maven Central (proxy missing or mirror not configured) | Sections 1 + 4 below |
| `Could not GET 'https://repo.maven.apache.org/maven2/...'` | Same — direct egress blocked | Sections 1 + 4 below |
| `PKIX path building failed: unable to find valid certification path` | Corporate proxy intercepts TLS with an internal CA the JDK doesn't trust | Section 2 below |
| `Connection refused` / `Connect timed out` | Proxy not configured or wrong | Section 1 below |
| `JAVA_HOME is set but does not point to a JDK 17+` | Wrong JDK selected | Section 3 below |

`scripts/build-health-check.sh` (or `.ps1`) walks the build chain layer
by layer (JDK → proxy → TLS → trust-store → mirror → wrapper → end-to-end)
and prints which step is the blocker. Run it before doing manual fixes.

### 1. Proxy not configured

Gradle does **not** auto-detect Windows / macOS system-proxy settings.
Configure it explicitly in your **user-level** `gradle.properties` so
every project on the machine inherits the same settings:

- Windows: `%USERPROFILE%\.gradle\gradle.properties`
- macOS / Linux: `~/.gradle/gradle.properties`

```properties
# user-level gradle.properties
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

### 2. Corporate TLS interception (`PKIX path building failed`)

If a corporate proxy intercepts HTTPS to scan content, it presents a
chain rooted in your org's internal CA. The JDK's bundled truststore
doesn't trust that root, so the TLS handshake fails. Two options:

**A. Import the corporate root into the JDK's `cacerts`** (per JDK
install, default password `changeit`):

```bash
# macOS / Linux
sudo keytool -importcert -alias corp-root \
        -file /path/to/corp-root.crt \
        -keystore "$JAVA_HOME/lib/security/cacerts" \
        -storepass changeit -noprompt
```

```powershell
# Windows -- adjust JAVA_HOME if needed
keytool -importcert -alias corp-root `
        -file C:\path\to\corp-root.crt `
        -keystore "$env:JAVA_HOME\lib\security\cacerts" `
        -storepass changeit -noprompt
```

The corporate root usually comes from your IT team or your browser's
certificate manager (export the issuer of any internal HTTPS site).
`build-health-check` can also tell you which root the proxy presents.

**B. Point the JVM at the OS-integrated trust store** (preferred —
big enterprises ship the corporate root through GPO/MDM into the
operating-system trust store, and this propagates CA rotations
automatically). Add one line to `gradle.properties`:

```properties
# Windows -- read the Windows Cert Store directly
systemProp.javax.net.ssl.trustStoreType=Windows-ROOT
```

```properties
# macOS -- read System + Login Keychains
systemProp.javax.net.ssl.trustStoreType=KeychainStore
```

The `trust-store` layer of `build-health-check` detects what's already
wired and (on Windows / macOS) offers to write the right line for you.

### 3. Wrong JDK or `JAVA_HOME` not set

`gradlew` resolves Java by checking `JAVA_HOME` first, then `PATH`. A
common Windows failure mode is `JAVA_HOME` pointing at JDK 11 (or 17)
while `java.exe` on `PATH` is 21 — the wrapper picks `JAVA_HOME` and
fails. Set `JAVA_HOME` to a JDK 17–25 install root in System →
Environment Variables, or temporarily in the current shell:

```powershell
# Windows (PowerShell, current shell only)
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17"
```

```bash
# macOS — pin to the 21 install via java_home helper
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
```

### 4. Maven Central blocked (need an internal mirror)

Corporate networks often block direct egress to `repo.maven.apache.org`
and expect builds to resolve through an internal Artifactory / Nexus
mirror. The fix is a user-wide Gradle init script that rewrites every
`mavenCentral()` call to the corporate URL. `build-health-check` will
offer to write it for you if it can find the URL automatically; if not,
you'll need to supply it.

**Where to find your org's Maven mirror URL (JFrog Artifactory):**

1. Log in to your corporate Artifactory.
2. Click the avatar icon (upper-right) → **Set Me Up**.
3. Pick **Maven** and follow the on-screen workflow. Artifactory walks
   you through selecting the right virtual / remote repository and
   displays the repo URL — typically
   `https://artifactory.<corp>.com/artifactory/<repo-name>/` — with
   any credential snippet you can paste into
   `~/.gradle/gradle.properties` verbatim.

The health check also auto-detects existing corporate Maven config in
the places IT typically drops it:

- `settings.xml` — checked in `~/.m2/`, `~/Downloads/`, and the project
  root. Both `<mirror>` entries and `<repository>` URLs inside
  `<profile>` blocks are recognised.
- `maven-wrapper.properties` — checked in `~/.m2/wrapper/`,
  `~/.mvn/wrapper/`, `~/Downloads/`, the project root, and
  `.mvn/wrapper/`.

If either is found, the URL is presented as a numbered option so you
don't have to re-type it. To point the scan at a non-standard location:

```bash
./scripts/build-health-check.sh --maven-config /path/to/settings.xml
./scripts/build-health-check.sh --maven-config ~/corp-tools/      # directory, depth 3
```

```powershell
.\scripts\build-health-check.ps1 -MavenConfigPath C:\custom\settings.xml
.\scripts\build-health-check.ps1 -MavenConfigPath C:\corp-tools\
```

**Manual init-script template** if the prompt is skipped — write to
`~/.gradle/init.d/corp-repos.gradle.kts`:

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
`repo.maven.apache.org` directly.

If the mirror requires credentials, add to
`~/.gradle/gradle.properties`:

```properties
orgInternalMavenUser=<username>
orgInternalMavenPassword=<access-token-or-api-key>
```

…and extend each `configureEach { maybeRewrite(this) }` block with a
`credentials { username = providers.gradleProperty("orgInternalMavenUser").get(); password = providers.gradleProperty("orgInternalMavenPassword").get() }`
clause.

After fixing whichever blocker the health check flagged, re-run
`build-health-check` to confirm green, then re-attempt the build.

---

## Harness internals (what `harness-builder` writes)

When the harness drives a benchmark build it writes an **overlay**
`~/.gradle/init.d/ai-bench-enterprise.gradle.kts`. The overlay is
deleted when the run completes.

```kotlin
allprojects {
    buildscript {
        repositories {
            clear()
            maven {
                url = uri(System.getenv("ARTIFACTORY_URL")!!)
                credentials {
                    username = System.getenv("ARTIFACTORY_USER")
                    password = System.getenv("ARTIFACTORY_TOKEN")
                }
            }
        }
    }
    repositories {
        clear()
        maven {
            url = uri(System.getenv("ARTIFACTORY_URL")!!)
            credentials {
                username = System.getenv("ARTIFACTORY_USER")
                password = System.getenv("ARTIFACTORY_TOKEN")
            }
        }
    }
}
```

…plus injects into `~/.gradle/gradle.properties`:

```
systemProp.https.proxyHost=${proxy-host}
systemProp.https.proxyPort=${proxy-port}
systemProp.http.nonProxyHosts=localhost|127.*|[::1]|${artifactory-host}
org.gradle.jvmargs=-Djavax.net.ssl.trustStore=${corp-truststore} -Djavax.net.ssl.trustStorePassword=***
```

Both are overlaid non-destructively: existing user config is backed up
and restored.

### Maven projects

Equivalent `~/.m2/settings.xml` overlay, written to
`settings-ai-bench.xml` and passed via `-s`:

```xml
<settings>
  <proxies>...</proxies>
  <servers>
    <server>
      <id>artifactory</id>
      <username>${env.ARTIFACTORY_USER}</username>
      <password>${env.ARTIFACTORY_TOKEN}</password>
    </server>
  </servers>
  <mirrors>
    <mirror>
      <id>artifactory</id>
      <mirrorOf>*</mirrorOf>
      <url>${env.ARTIFACTORY_URL}</url>
    </mirror>
  </mirrors>
</settings>
```

### Secrets

Secrets never live in the repo or YAML config. The builder reads from
environment variables:

- `ARTIFACTORY_URL`, `ARTIFACTORY_USER`, `ARTIFACTORY_TOKEN`
- `HTTPS_PROXY`, `NO_PROXY`
- `CORP_TRUSTSTORE_PATH`, `CORP_TRUSTSTORE_PASSWORD`

The webui has a one-time "import from env" button that stashes these in
the OS keychain (via `keyring` per platform) for subsequent headless
CLI runs.

### Detection

`harness-builder` auto-detects build system by presence of
`settings.gradle*` / `build.gradle*` / `pom.xml`. Mixed repos
(submodules of both) are handled by recursion.
