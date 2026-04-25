#!/usr/bin/env bash
#
# Stop the bench-webui process started by start-bench-tools.sh.
#
# Reads the PID file written by start-bench-tools.sh
# (default: $INSTALL_DIR/bench-webui.pid where INSTALL_DIR=~/ai-bench),
# verifies the PID still belongs to a live process, sends SIGTERM, and
# removes the PID file. Exits 0 if the process was stopped or already
# absent; exits non-zero only if the PID file points at a live process
# that refused to die.
#
# Re-running is safe -- a missing PID file or stale entry is reported
# and the script exits 0.
#
# Environment variables:
#   INSTALL_DIR  Where to look for bench-webui.pid (default: ~/ai-bench)

set -eu

INSTALL_DIR="${INSTALL_DIR:-$HOME/ai-bench}"
pid_file="$INSTALL_DIR/bench-webui.pid"

RED=$'\033[0;31m'; GRN=$'\033[0;32m'; YEL=$'\033[0;33m'; CYN=$'\033[0;36m'; CLR=$'\033[0m'
ok()   { printf "${GRN}[ok]${CLR}   %s\n" "$1"; }
info() { printf "${CYN}[info]${CLR} %s\n" "$1"; }
warn() { printf "${YEL}[warn]${CLR} %s\n" "$1"; }
err()  { printf "${RED}ERROR:${CLR} %s\n" "$1" >&2; exit 1; }

if [ ! -f "$pid_file" ]; then
    info "No PID file at $pid_file -- bench-webui is not tracked here. Nothing to stop."
    exit 0
fi

pid=$(cat "$pid_file" 2>/dev/null || true)
if [ -z "$pid" ]; then
    warn "PID file $pid_file is empty -- removing."
    rm -f "$pid_file"
    exit 0
fi

if ! kill -0 "$pid" 2>/dev/null; then
    warn "Process $pid (from $pid_file) is not running -- removing stale PID file."
    rm -f "$pid_file"
    exit 0
fi

info "Sending SIGTERM to bench-webui PID $pid..."
kill -TERM "$pid" 2>/dev/null || err "Failed to send SIGTERM to PID $pid"

# Spring Boot generally shuts down within 5-10s. Wait up to 15s, then
# escalate to SIGKILL if still alive.
for _ in $(seq 1 30); do
    if ! kill -0 "$pid" 2>/dev/null; then
        rm -f "$pid_file"
        ok "bench-webui (PID $pid) stopped."
        exit 0
    fi
    sleep 0.5
done

warn "PID $pid did not exit within 15s -- escalating to SIGKILL."
kill -KILL "$pid" 2>/dev/null || true
sleep 1
if kill -0 "$pid" 2>/dev/null; then
    err "PID $pid still alive after SIGKILL. Investigate manually."
fi
rm -f "$pid_file"
ok "bench-webui (PID $pid) force-killed."
