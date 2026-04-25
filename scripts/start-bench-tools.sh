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
    if ! command -v gh >/dev/null 2>&1; then
        err "$INSTALL_DIR is missing one or both release artifacts and 'gh' is not on PATH.
Install gh (https://cli.github.com) OR download manually from
https://github.com/$REPO/releases/latest into $INSTALL_DIR, then re-run."
    fi
    # gh release download without an explicit tag defaults to "latest
    # STABLE", which excludes prereleases. Resolve the most recent
    # release tag (prereleases included) first, then download by tag.
    info "Resolving most recent release tag from $REPO..."
    latest_tag=$(gh release list --repo "$REPO" --limit 1 --json tagName -q '.[0].tagName' 2>/dev/null || true)
    [ -n "$latest_tag" ] || err "No releases published in $REPO."
    info "Downloading $latest_tag from $REPO into $INSTALL_DIR..."
    gh release download "$latest_tag" --repo "$REPO" \
        --pattern 'bench-cli-*.zip' --pattern 'bench-webui-*.jar' --clobber
    cli_zip=$(ls bench-cli-*.zip 2>/dev/null | head -1 || true)
    webui_jar=$(ls bench-webui-*.jar 2>/dev/null | head -1 || true)
    [ -n "$cli_zip" ]   || err "bench-cli zip still missing after gh release download."
    [ -n "$webui_jar" ] || err "bench-webui jar still missing after gh release download."
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
