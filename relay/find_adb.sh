#!/usr/bin/env bash
# Prints the adb binary to use (source this file and call find_adb, or run it directly).
# Inside WSL the phone is usually attached to the Windows adb server, which an adb inside WSL
# cannot see, so the Windows SDK's adb.exe is preferred there. Override with ADB=/path/to/adb.

find_adb() {
    if [[ -n "${ADB:-}" ]]; then
        echo "$ADB"
        return
    fi
    if grep -qi microsoft /proc/version 2>/dev/null && command -v cmd.exe > /dev/null; then
        local local_appdata candidate
        local_appdata="$(cd /mnt/c 2>/dev/null && cmd.exe /c 'echo %LOCALAPPDATA%' 2>/dev/null | tr -d '\r')"
        candidate="$(wslpath -u "$local_appdata" 2>/dev/null)/Android/Sdk/platform-tools/adb.exe"
        if [[ -n "$local_appdata" && -x "$candidate" ]]; then
            echo "$candidate"
            return
        fi
    fi
    echo adb
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
    find_adb
fi
