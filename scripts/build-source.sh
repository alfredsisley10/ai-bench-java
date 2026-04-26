#!/usr/bin/env bash
#
# Build every sub-project from source, in dependency order.
#
# Replaces the manual sequence:
#   cd banking-app   && ./gradlew build
#   cd ../bench-harness && ./gradlew build
#   cd ../bench-cli  && ./gradlew build
#   cd ../bench-webui && ./gradlew build
#
# Pass extra Gradle args after `--`:
#   ./scripts/build-source.sh -- clean test
#
# Skip subprojects with --skip:
#   ./scripts/build-source.sh --skip banking-app

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

GRN=$'\033[0;32m'; RED=$'\033[0;31m'; CYN=$'\033[0;36m'; CLR=$'\033[0m'
info() { printf "${CYN}==> %s${CLR}\n" "$1"; }
ok()   { printf "${GRN}OK %s${CLR}\n" "$1"; }
err()  { printf "${RED}FAIL %s${CLR}\n" "$1" >&2; exit 1; }

PROJECTS=(banking-app bench-harness bench-cli bench-webui)
SKIP=""
EXTRA_ARGS=("build")
seen_dashdash=0
for arg in "$@"; do
    if [ "$seen_dashdash" = "1" ]; then
        EXTRA_ARGS+=("$arg"); continue
    fi
    case "$arg" in
        --) seen_dashdash=1; EXTRA_ARGS=() ;;
        --skip=*) SKIP="${arg#--skip=}" ;;
        --skip)   err "--skip requires =VALUE form, e.g. --skip=banking-app" ;;
        -h|--help)
            sed -n '2,15p' "$0"; exit 0 ;;
        *) err "Unknown arg: $arg (use --skip=NAME or -- ARGS for Gradle)" ;;
    esac
done

command -v java >/dev/null 2>&1 || err "java not on PATH -- install JDK 17-25"
java -version 2>&1 | head -1

for proj in "${PROJECTS[@]}"; do
    case ",$SKIP," in *",$proj,"*) info "skip $proj"; continue ;; esac
    [ -x "$REPO_ROOT/$proj/gradlew" ] || err "$proj/gradlew missing"
    info "$proj :: ./gradlew ${EXTRA_ARGS[*]}"
    ( cd "$REPO_ROOT/$proj" && ./gradlew "${EXTRA_ARGS[@]}" )
    ok "$proj"
done

echo
ok "All builds completed."
