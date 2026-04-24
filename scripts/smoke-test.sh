#!/usr/bin/env bash
#
# ai-bench-java smoke test. Run from repo root:
#   ./scripts/smoke-test.sh
#
# Assumes Java 21 and Gradle 8.10+ on PATH. The banking-app does not yet ship
# with a wrapper binary (see docs/project-context/BUILD-PLAN.md Phase 1 note)
# — run `gradle wrapper` once in banking-app/ to populate gradle-wrapper.jar.

set -euo pipefail

die()  { echo "FAIL: $*" 1>&2; exit 1; }
note() { echo "== $* =="; }

command -v java >/dev/null 2>&1 || die "java not found — install JDK 21"
java -version 2>&1 | head -1

if ! command -v gradle >/dev/null 2>&1; then
    if [[ -x banking-app/gradlew ]]; then
        GRADLE_CMD=$(pwd)/banking-app/gradlew
    else
        die "gradle not found and banking-app/gradlew missing — install Gradle or generate the wrapper"
    fi
else
    GRADLE_CMD=gradle
fi

note "Bootstrapping Gradle wrappers (if missing)"
for dir in banking-app bench-harness bench-cli bench-webui; do
    if [[ ! -f "$dir/gradlew" ]]; then
        ( cd "$dir" && "$GRADLE_CMD" wrapper --gradle-version 8.10.2 )
    fi
done

note "banking-app :shared-domain:test"
( cd banking-app && ./gradlew --no-daemon :shared-domain:test )

note "banking-app :ledger-core:compileJava"
( cd banking-app && ./gradlew --no-daemon :ledger-core:compileJava )

note "banking-app :payments-hub:test"
( cd banking-app && ./gradlew --no-daemon :payments-hub:test )

note "bench-harness :harness-core:compileKotlin"
( cd bench-harness && ./gradlew --no-daemon :harness-core:compileKotlin )

note "bench-harness :harness-llm:llm-api:compileKotlin"
( cd bench-harness && ./gradlew --no-daemon :harness-llm:llm-api:compileKotlin )

note "bench-cli build"
( cd bench-cli && ./gradlew --no-daemon build --warning-mode=summary )

note "bench-webui build"
( cd bench-webui && ./gradlew --no-daemon build --warning-mode=summary )

echo
echo "SMOKE OK."
