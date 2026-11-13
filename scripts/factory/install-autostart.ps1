<#
.SYNOPSIS
  Registers (or removes) the "FT8AF factory loop" logon task on this machine.

.DESCRIPTION
  At logon of the current user, two minutes after sign-in, the task runs
  start-factory.ps1 from the main checkout, which opens the factory loop in a
  Windows Terminal tab. It runs only while the user is signed in (Claude Code
  runs as you and needs your desktop and credentials), so after a reboot it
  starts when you sign in, or straight away when Windows auto sign-in is on.

  Re-running it replaces the task. -Remove unregisters it.

    powershell -NoProfile -ExecutionPolicy Bypass -File scripts\factory\install-autostart.ps1
#>
param(
    [string]$Repo = "C:\Users\burns\Projects\NEXT-FT8CN",
    [switch]$Remove
)

$name = "FT8AF factory loop"
if ($Remove) {
    Unregister-ScheduledTask -TaskName $name -Confirm:$false -ErrorAction SilentlyContinue
    Write-Output "removed '$name'"
    exit 0
}

$script = Join-Path $Repo "scripts\factory\start-factory.ps1"
if (-not (Test-Path $script)) { throw "no launcher at $script; is the main checkout on a dev that has scripts/factory?" }

$user = "$env:USERDOMAIN\$env:USERNAME"
$action = New-ScheduledTaskAction -Execute "powershell.exe" `
    -Argument "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$script`" -Repo `"$Repo`""
$trigger = New-ScheduledTaskTrigger -AtLogOn -User $user
$trigger.Delay = "PT2M"
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
    -ExecutionTimeLimit (New-TimeSpan -Minutes 30) -MultipleInstances IgnoreNew -StartWhenAvailable
$principal = New-ScheduledTaskPrincipal -UserId $user -LogonType Interactive -RunLevel Limited

Register-ScheduledTask -TaskName $name -Action $action -Trigger $trigger -Settings $settings `
    -Principal $principal -Force `
    -Description "Opens the FT8AF software-factory loop (claude /loop /ft8af-factory) in a terminal tab at logon. scripts/factory/start-factory.ps1; log in %LOCALAPPDATA%\ft8af-factory." | Out-Null
Write-Output "registered '$name' for $user (at logon + 2 min)"
