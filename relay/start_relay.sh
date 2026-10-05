#!/usr/bin/env bash
# Starts the relay watchdog in a detached tmux session; safe to run repeatedly.
# Windows runs this at logon through relay/windows/start-hermes-voice-relay.vbs.
#   relay/start_relay.sh            start (no-op if already running)
#   relay/start_relay.sh --restart  restart, e.g. after updating the relay code or the token
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SESSION="hermes-voice-relay"

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
