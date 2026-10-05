#!/usr/bin/env bash
# Creates (or reuses) the relay pairing token and hands it to the Fold7 app over USB debugging.
# The token lives only in ~/.config/hermes-voice (mode 600) and the app's Keystore-encrypted storage.
set -euo pipefail

TOKEN_FILE="${RELAY_TOKEN_FILE:-$HOME/.config/hermes-voice/relay_token}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=find_adb.sh
. "$HERE/find_adb.sh"
ADB="$(find_adb)"

if [[ "${1:-}" == "--rotate" || ! -s "$TOKEN_FILE" ]]; then
    mkdir -p "$(dirname "$TOKEN_FILE")"
    chmod 700 "$(dirname "$TOKEN_FILE")"
    (umask 077 && python3 -c 'import secrets; print(secrets.token_urlsafe(32))' > "$TOKEN_FILE")
    echo "new pairing token written to $TOKEN_FILE (restart the relay to use it)"
fi

"$ADB" shell am start -n com.architact.hermesvoice/.MainActivity --es pairing_token "$(cat "$TOKEN_FILE")" > /dev/null
echo "pairing token sent to the device; the app shows '페어링되었습니다'"
