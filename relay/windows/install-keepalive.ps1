# Registers a per-user scheduled task that runs the hidden relay launcher every 5 minutes, so the
# relay (and anything ~/.config/hermes-voice/pre-start.sh brings up) returns after WSL or PC restarts.
# Run once in PowerShell after copying start-hermes-voice-relay.vbs into shell:startup.
$vbs = "$env:APPDATA\Microsoft\Windows\Start Menu\Programs\Startup\start-hermes-voice-relay.vbs"
if (-not (Test-Path $vbs)) { throw "Copy start-hermes-voice-relay.vbs to $vbs first." }
$action = New-ScheduledTaskAction -Execute "wscript.exe" -Argument "`"$vbs`""
$trigger = New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(1) -RepetitionInterval (New-TimeSpan -Minutes 5)
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable -MultipleInstances IgnoreNew -ExecutionTimeLimit (New-TimeSpan -Minutes 2)
Register-ScheduledTask -TaskName "Hermes Voice Relay Keepalive" -Description "Every 5 minutes: make sure the Hermes Voice relay (WSL) is running." -Action $action -Trigger $trigger -Settings $settings -Force | Out-Null
Write-Host "Registered: Hermes Voice Relay Keepalive (every 5 minutes)"
