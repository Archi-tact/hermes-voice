' Starts the Hermes Voice relay in WSL at Windows logon (copy into shell:startup).
Set WshShell = CreateObject("WScript.Shell")
cmd = "wsl.exe -d Ubuntu -- bash -lc ""mkdir -p $HOME/.hermes/logs; echo [$(date -Is)] Windows Startup: Hermes Voice relay launcher invoked >> $HOME/.hermes/logs/hermes-voice-startup.log; $HOME/projects/hermes-voice-android/relay/start_relay.sh >> $HOME/.hermes/logs/hermes-voice-startup.log 2>&1"""
WshShell.Run cmd, 0, False
