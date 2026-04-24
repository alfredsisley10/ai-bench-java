# Scripts

Ops helpers used by the harness and by humans.

## build-health-check (PowerShell + bash)

Pre-build sanity check for a fresh machine. Verifies JDK 21, proxy
configuration, reachability to every artifact repository Gradle hits at
build time, TLS chain (detects corporate-CA interception), and the
Gradle distribution download. Prints `[PASS]` / `[FAIL]` per check with
remediation hints; exits non-zero if any blocker is found.

```powershell
# Windows (PowerShell)
.\scripts\build-health-check.ps1
```

```bash
# macOS / Linux
./scripts/build-health-check.sh
```

Both scripts run identical checks and produce equivalent output, so CI
or onboarding docs can call whichever matches the platform without
behavioral differences. Run this before the first `gradlew build` on any
new machine — especially behind a corporate proxy. See the README's
"Behind a corporate proxy" section for fixing the failures it surfaces.

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
