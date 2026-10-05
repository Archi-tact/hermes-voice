#!/usr/bin/env bash
# Keeps the Hermes Voice relay running and restores the USB `adb reverse` mapping whenever the
# Fold7 is plugged in. Runs forever inside the tmux session created by start_relay.sh.
set -u

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# The Hermes venv has edge_tts for the PC voices; fall back to the system Python without them.
PY="${HERMES_PY:-$HOME/.hermes/hermes-agent/.venv/bin/python}"
[[ -x "$PY" ]] || PY=python3
# Under WSL this resolves to the Windows SDK adb.exe (the phone is attached to the Windows adb server).
# shellcheck source=find_adb.sh
. "$HERE/find_adb.sh"
ADB="$(find_adb)"
PORT="${RELAY_PORT:-8765}"
LOG="$HOME/.hermes/logs/hermes-voice-relay.log"
mkdir -p "$(dirname "$LOG")"

log() { echo "[$(date -Is)] $*" >> "$LOG"; }

relay_pid=""

start_relay() {
    (
        set -a
        # shellcheck disable=SC1091
        [[ -f "$HOME/.hermes/.env" ]] && . "$HOME/.hermes/.env"
        set +a
        exec "$PY" -u "$HERE/hermes_voice_relay.py"
    ) >> "$LOG" 2>&1 &
    relay_pid=$!
    log "watchdog: relay started pid=$relay_pid"
}

ensure_reverse() {
    [[ -x "$ADB" ]] || return 0
    [[ "$("$ADB" get-state 2>/dev/null | tr -d '\r')" == "device" ]] || return 0
    "$ADB" reverse --list 2>/dev/null | grep -q "tcp:$PORT tcp:$PORT" && return 0
    if "$ADB" reverse "tcp:$PORT" "tcp:$PORT" > /dev/null 2>&1; then
        log "watchdog: adb reverse tcp:$PORT restored"
    fi
}

trap '[[ -n "$relay_pid" ]] && kill "$relay_pid" 2>/dev/null; exit 0' TERM INT

log "watchdog: starting (python=$PY)"
while true; do
    if [[ -z "$relay_pid" ]] || ! kill -0 "$relay_pid" 2>/dev/null; then
        [[ -n "$relay_pid" ]] && log "watchdog: relay exited; restarting"
        start_relay
        sleep 2
    fi
    ensure_reverse
    sleep 15
done
