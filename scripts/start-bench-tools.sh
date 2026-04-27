#!/usr/bin/env bash
#
# Boot the pre-built ai-bench-java tools on macOS / Linux.
#
# What this does, in order:
#   1. Verifies a JDK 17-25 is on PATH.
#   2. If $INSTALL_DIR (default ~/ai-bench) is missing the release
#      artifacts, copies them out of the repo's dist/ when present,
#      otherwise fetches them from the GitHub Releases API via curl
#      (no `gh` CLI dependency).
#   3. Unzips bench-cli if it's still in zip form.
#   4. Launches bench-webui in the background, captures its PID +
#      log path, prints the URL.
#   5. Prints the bench-cli launcher path and a one-liner to add it
#      to PATH for the current session.
#
# Re-running is safe: if bench-webui is already up (PID file matches a
# live process), the script reports that and exits without launching a
# duplicate. Stop bench-webui with: kill $(cat ~/ai-bench/bench-webui.pid)
#
# Environment variables:
#   INSTALL_DIR  Override where artifacts live (default: ~/ai-bench)
#   REPO         GitHub repo to download from (default: alfredsisley10/ai-bench-java)

set -eu

# Resolve the script's repo location BEFORE the cd "$INSTALL_DIR"
# below; otherwise relative-$0 invocations like
# `./scripts/start-bench-tools.sh` fail to locate ../dist/ from the
# install dir.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"
REPO_DIST="$SCRIPT_DIR/../dist"
[ -d "$REPO_DIST" ] && REPO_DIST="$(cd "$REPO_DIST" && pwd)"

INSTALL_DIR="${INSTALL_DIR:-$HOME/ai-bench}"
REPO="${REPO:-alfredsisley10/ai-bench-java}"

RED=$'\033[0;31m'; GRN=$'\033[0;32m'; YEL=$'\033[0;33m'; CYN=$'\033[0;36m'; CLR=$'\033[0m'
err()  { printf "${RED}ERROR:${CLR} %s\n" "$1" >&2; exit 1; }
info() { printf "${CYN}[info]${CLR} %s\n" "$1"; }
ok()   { printf "${GRN}[ok]${CLR}   %s\n" "$1"; }
warn() { printf "${YEL}[warn]${CLR} %s\n" "$1"; }

# --- 1. JDK precheck --------------------------------------------------
# Find the first matching artifact in a directory; prints the BASENAME (or
# nothing when there's no match). Used for both the local INSTALL_DIR and
# the repo's dist/ scan.
find_artifact() {
    local dir="$1" pattern="$2" hit
    hit=$(find "$dir" -maxdepth 1 -name "$pattern" -print 2>/dev/null | head -1)
    [ -n "$hit" ] && basename "$hit"
}

command -v java >/dev/null 2>&1 \
    || err "java is not on PATH. Install JDK 17-25 (Temurin / OpenJDK / Corretto / Zulu) and re-run."
ver_line=$(java -version 2>&1 | head -1)
java_major=$(printf '%s' "$ver_line" | awk -F'"' '{ split($2, a, "."); print a[1] }')
if [ -z "$java_major" ] || [ "$java_major" -lt 17 ] 2>/dev/null || [ "$java_major" -gt 25 ] 2>/dev/null; then
    err "JDK ${java_major:-?} found ($ver_line); need 17-25."
fi
ok "JDK $java_major detected ($ver_line)"

# --- 2. Locate / download artifacts -----------------------------------
mkdir -p "$INSTALL_DIR"
cd "$INSTALL_DIR"

cli_zip=$(find_artifact "$INSTALL_DIR" 'bench-cli-*.zip')
webui_jar=$(find_artifact "$INSTALL_DIR" 'bench-webui-*.jar')

# Cache lookup: if the script lives inside a repo checkout that ships
# pre-built artifacts under dist/, copy them into INSTALL_DIR rather
# than downloading. This makes 'git clone' alone sufficient for
# enterprise users who can reach the git remote but not GitHub Releases.
#
# Staleness check: if the LOCAL artifacts already exist but the dist/
# copy is NEWER (mtime), overwrite them. Without this, an operator who
# git-pulled a newer dist/jar but already had a previous run's jar in
# INSTALL_DIR would silently keep running the older binary -- the
# class of bug that masked the /llm 500 fix on Windows machines that
# had been booted from an earlier release.
repo_dist="$REPO_DIST"
repo_cli=$(find_artifact "$repo_dist" 'bench-cli-*.zip')
repo_webui=$(find_artifact "$repo_dist" 'bench-webui-*.jar')

differs() {
    # Returns 0 (true) if $2 is missing OR $1's size differs from $2's
    # OR $1 is newer than $2. Size + mtime is more reliable than mtime
    # alone: a Windows machine that booted from an older release jar,
    # then later git-pulled a newer dist/jar, can end up with matching
    # mtimes (cp preserves nothing by default; git can preserve commit
    # time depending on core.checkstat). Size catches the content-
    # change case the mtime check would miss.
    [ -f "$1" ] || return 1
    [ -f "$2" ] || return 0
    src_size=$(wc -c < "$1" | tr -d ' ')
    dst_size=$(wc -c < "$2" | tr -d ' ')
    [ "$src_size" != "$dst_size" ] && return 0
    [ "$1" -nt "$2" ]
}

if [ -n "$repo_cli" ] && [ -n "$repo_webui" ]; then
    refreshed_cli=0
    refreshed_webui=0
    if [ -z "$cli_zip" ] || differs "$repo_dist/$repo_cli" "$INSTALL_DIR/$cli_zip"; then
        # Wipe any unzipped bench-cli-* dir so the next "Unzip" step
        # below recreates it from the fresher zip.
        rm -rf "$INSTALL_DIR"/bench-cli-*/
        cp "$repo_dist/$repo_cli" "$INSTALL_DIR/"
        cli_zip="$repo_cli"
        refreshed_cli=1
    fi
    if [ -z "$webui_jar" ] || differs "$repo_dist/$repo_webui" "$INSTALL_DIR/$webui_jar"; then
        cp "$repo_dist/$repo_webui" "$INSTALL_DIR/"
        webui_jar="$repo_webui"
        refreshed_webui=1
    fi
    if [ "$refreshed_cli" = "1" ] || [ "$refreshed_webui" = "1" ]; then
        ok "Refreshed from $repo_dist (cli=$refreshed_cli, webui=$refreshed_webui)."
    fi
fi

if [ -z "$cli_zip" ] || [ -z "$webui_jar" ]; then
    # Use curl + python3 (universal on macOS / Linux) to avoid an
    # external GitHub-CLI dependency. /releases?per_page=1 returns the
    # most recent release including prereleases; /releases/latest would
    # skip prereleases and is therefore avoided.
    command -v curl    >/dev/null 2>&1 || err "'curl' is required but not on PATH."
    command -v python3 >/dev/null 2>&1 || err "'python3' is required to parse the GitHub API response but is not on PATH."

    api_url="https://api.github.com/repos/$REPO/releases?per_page=1"
    info "Resolving most recent release via $api_url ..."
    release_json=$(curl -fsSL -H 'User-Agent: ai-bench-java-startup' "$api_url" 2>/dev/null) \
        || err "GitHub API request failed for $api_url"

    # Emit: tag<TAB>name<TAB>url for each asset, one record per line.
    parsed=$(printf '%s' "$release_json" | python3 -c '
import json, sys
data = json.loads(sys.stdin.read())
if not data:
    sys.exit("no releases found in API response")
r = data[0]
for a in r["assets"]:
    print(r["tag_name"] + "\t" + a["name"] + "\t" + a["browser_download_url"])
') || err "Failed to parse release JSON"

    tag=$(printf '%s\n' "$parsed" | head -1 | cut -f1)
    info "Latest release: $tag -- downloading assets..."

    while IFS=$'\t' read -r _t name url; do
        case "$name" in
            bench-cli-*.zip|bench-webui-*.jar)
                info "  $name"
                curl -fsSL -H 'User-Agent: ai-bench-java-startup' -o "$name" "$url" \
                    || err "Download failed: $url"
                ;;
        esac
    done <<< "$parsed"

    cli_zip=$(find_artifact "$INSTALL_DIR" 'bench-cli-*.zip')
    webui_jar=$(find_artifact "$INSTALL_DIR" 'bench-webui-*.jar')
    [ -n "$cli_zip" ]   || err "bench-cli zip still missing after download."
    [ -n "$webui_jar" ] || err "bench-webui jar still missing after download."
    ok "Downloaded $cli_zip + $webui_jar"
else
    ok "Found existing artifacts: $cli_zip + $webui_jar"
fi

# --- 3. Unzip bench-cli if needed -------------------------------------
cli_dir=$(ls -d bench-cli-*/ 2>/dev/null | head -1 || true)
if [ -z "$cli_dir" ]; then
    info "Unzipping $cli_zip..."
    command -v unzip >/dev/null 2>&1 || err "unzip is not on PATH; install it first."
    unzip -q "$cli_zip"
    cli_dir=$(ls -d bench-cli-*/ | head -1)
fi
cli_bin="$INSTALL_DIR/${cli_dir%/}/bin/bench-cli"
[ -x "$cli_bin" ] || err "Expected bench-cli launcher at $cli_bin but it isn't executable."

# --- 4. Launch bench-webui --------------------------------------------
log_file="$INSTALL_DIR/bench-webui.log"
pid_file="$INSTALL_DIR/bench-webui.pid"

if [ -f "$pid_file" ]; then
    existing_pid=$(cat "$pid_file" 2>/dev/null || true)
    if [ -n "$existing_pid" ] && kill -0 "$existing_pid" 2>/dev/null; then
        warn "bench-webui is already running (PID $existing_pid)."
        info "  Logs:    $log_file"
        info "  Web UI:  http://localhost:7777"
        info "  Stop:    kill $existing_pid"
        echo
        info "bench-cli launcher: $cli_bin"
        info "Add to PATH for this shell:  export PATH=\"\$PATH:$INSTALL_DIR/${cli_dir%/}/bin\""
        exit 0
    fi
    rm -f "$pid_file"
fi

info "Starting bench-webui ..."
nohup java -jar "$webui_jar" >"$log_file" 2>&1 &
new_pid=$!
echo "$new_pid" > "$pid_file"

# Brief wait so we can detect immediate failures (bind error, jar broken, etc.)
sleep 2
if ! kill -0 "$new_pid" 2>/dev/null; then
    rm -f "$pid_file"
    err "bench-webui exited within 2 seconds. Last 20 lines of $log_file:
$(tail -20 "$log_file" 2>/dev/null)"
fi
ok "bench-webui PID $new_pid"
info "  Logs:    $log_file"
info "  Web UI:  http://localhost:7777  (Spring Boot may take ~10-30s to finish booting)"
info "  Stop:    kill $new_pid"

# --- 5. bench-cli pointer --------------------------------------------
echo
info "bench-cli launcher: $cli_bin"
info "Add to PATH for this shell:  export PATH=\"\$PATH:$INSTALL_DIR/${cli_dir%/}/bin\""
info "Run:                          bench-cli --help"
