#!/usr/bin/env bash
#
# Boot the pre-built ai-bench-java tools on macOS / Linux.
#
# What this does, in order:
#   1. Verifies a JDK 17-25 is on PATH.
#   2. If $INSTALL_DIR (default ~/ai-bench) is missing the release
#      artifacts, downloads them via `gh release download`.
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

INSTALL_DIR="${INSTALL_DIR:-$HOME/ai-bench}"
REPO="${REPO:-alfredsisley10/ai-bench-java}"

RED=$'\033[0;31m'; GRN=$'\033[0;32m'; YEL=$'\033[0;33m'; CYN=$'\033[0;36m'; CLR=$'\033[0m'
err()  { printf "${RED}ERROR:${CLR} %s\n" "$1" >&2; exit 1; }
info() { printf "${CYN}[info]${CLR} %s\n" "$1"; }
ok()   { printf "${GRN}[ok]${CLR}   %s\n" "$1"; }
warn() { printf "${YEL}[warn]${CLR} %s\n" "$1"; }

# --- 1. JDK precheck --------------------------------------------------
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

cli_zip=$(ls bench-cli-*.zip 2>/dev/null | head -1 || true)
webui_jar=$(ls bench-webui-*.jar 2>/dev/null | head -1 || true)
if [ -z "$cli_zip" ] || [ -z "$webui_jar" ]; then
    # Cache lookup: if the script lives inside a repo checkout that
    # ships pre-built artifacts under dist/, copy them into INSTALL_DIR
    # rather than downloading. This makes 'git clone' alone sufficient
    # for enterprise users who can reach the git remote but not GitHub
    # Releases.
    repo_dist="$(cd "$(dirname "$0")/.." && pwd)/dist"
    if [ -d "$repo_dist" ]; then
        repo_cli=$(ls "$repo_dist"/bench-cli-*.zip 2>/dev/null | head -1 || true)
        repo_webui=$(ls "$repo_dist"/bench-webui-*.jar 2>/dev/null | head -1 || true)
        if [ -n "$repo_cli" ] && [ -n "$repo_webui" ]; then
            info "Found pre-built artifacts in $repo_dist -- copying into $INSTALL_DIR..."
            cp "$repo_cli" "$repo_webui" "$INSTALL_DIR/"
            cli_zip=$(basename "$repo_cli")
            webui_jar=$(basename "$repo_webui")
            ok "Copied $cli_zip + $webui_jar from repo dist/."
        fi
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

    cli_zip=$(ls bench-cli-*.zip 2>/dev/null | head -1 || true)
    webui_jar=$(ls bench-webui-*.jar 2>/dev/null | head -1 || true)
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
