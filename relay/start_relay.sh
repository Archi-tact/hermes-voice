#!/usr/bin/env bash
# Starts the relay watchdog in a detached tmux session; safe to run repeatedly (Windows runs it at
# logon and every few minutes through relay/windows/, so the relay comes back after WSL restarts).
#   relay/start_relay.sh            start (no-op if already running)
#   relay/start_relay.sh --restart  restart, e.g. after updating the relay code or the token
# If ~/.config/hermes-voice/pre-start.sh exists it runs first (e.g. to bring up Tailscale).
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SESSION="hermes-voice-relay"
PRE_START="$HOME/.config/hermes-voice/pre-start.sh"

if [[ -x "$PRE_START" ]]; then
    "$PRE_START" || echo "pre-start hook failed (continuing)" >&2
fi

if [[ "${1:-}" == "--restart" ]] && tmux has-session -t "$SESSION" 2>/dev/null; then
    tmux kill-session -t "$SESSION"
    pkill -f "$HERE/hermes_voice_relay.py" 2>/dev/null || true
    sleep 1
fi

if tmux has-session -t "$SESSION" 2>/dev/null; then
    echo "Hermes Voice relay already running in tmux session: $SESSION"
    exit 0
fi

tmux new-session -d -s "$SESSION" "bash '$HERE/relay_watchdog.sh'"
echo "Started Hermes Voice relay in tmux session: $SESSION (log: ~/.hermes/logs/hermes-voice-relay.log)"
