#!/usr/bin/env bash
#
# ai-bench-java smoke test. Run from repo root:
#   ./scripts/smoke-test.sh
#
# Compiles the harness/CLI/UI core modules and exercises a small subset
# of banking-app tests so a broken first checkout fails loudly.
#
# Prerequisites: a JDK 17-25 on PATH. Each sub-project ships its own
# Gradle wrapper (9.4.x) so no separate Gradle install is needed.

set -euo pipefail

die()  { echo "FAIL: $*" 1>&2; exit 1; }
note() { echo "== $* =="; }

command -v java >/dev/null 2>&1 || die "java not found -- install JDK 17-25"
java -version 2>&1 | head -1

# Every sub-project must already have a Gradle wrapper checked in.
# Generating one from a system 'gradle' just to bootstrap is a pre-2024
# crutch we no longer need.
for dir in banking-app bench-harness bench-cli bench-webui; do
    [[ -x "$dir/gradlew" ]] || die "missing $dir/gradlew (re-clone or run 'git restore $dir/gradlew')"
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
