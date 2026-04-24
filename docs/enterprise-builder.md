# Enterprise builder

`harness-builder` has to build repositories that were never meant to be built outside the corporate network. That means:

- HTTPS traffic must go through a corporate forward proxy (`HTTPS_PROXY`, often with authentication).
- Dependency resolution must be redirected from `repo.maven.apache.org` / `plugins.gradle.org` to an internal Artifactory / Nexus mirror.
- TLS to the mirror requires the corporate CA bundle.
- Some builds gate on corporate credentials (Artifactory token, NPM enterprise token).

## Gradle projects

`harness-builder` writes an **overlay** `~/.gradle/init.d/ai-bench-enterprise.gradle.kts` on each run. It's deleted when the run completes.

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

And injects into `~/.gradle/gradle.properties`:

```
systemProp.https.proxyHost=${proxy-host}
systemProp.https.proxyPort=${proxy-port}
systemProp.http.nonProxyHosts=localhost|127.*|[::1]|${artifactory-host}
org.gradle.jvmargs=-Djavax.net.ssl.trustStore=${corp-truststore} -Djavax.net.ssl.trustStorePassword=***
```

Both are overlaid non-destructively: existing user config is backed up and restored.

## Maven projects

Equivalent `~/.m2/settings.xml` overlay, written to `settings-ai-bench.xml` and passed via `-s`:

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

## Secrets

Secrets never live in the repo or YAML config. The builder reads:
- `ARTIFACTORY_URL`, `ARTIFACTORY_USER`, `ARTIFACTORY_TOKEN`
- `HTTPS_PROXY`, `NO_PROXY`
- `CORP_TRUSTSTORE_PATH`, `CORP_TRUSTSTORE_PASSWORD`

...from environment. The webui has a one-time "import from env" button that stashes these in the OS keychain (via `keyring` lib on each platform) for subsequent headless CLI runs.

## Detection

`harness-builder` auto-detects build system by presence of `settings.gradle*` / `build.gradle*` / `pom.xml`. Mixed repos (submodules of both) are handled by recursion.
