<#
.SYNOPSIS
  Starts the FT8AF software-factory loop in a visible Windows Terminal tab.

.DESCRIPTION
  Run at logon by the "FT8AF factory loop" scheduled task (see
  install-autostart.ps1), or by hand. It:
    1. does nothing if a factory session is already running (a claude.exe
       whose command line holds "/ft8af-factory");
    2. waits for the network and a working GitHub login (up to -WaitMinutes),
       and nudges WSL so the self-hosted runners are up;
    3. fast-forwards the main checkout to origin/dev, but only when it is on
       dev and clean, so the loop reads the newest skill;
    4. opens a terminal tab running run-loop.cmd, which sets FT8AF_FACTORY=1
       (the guard hook, guard.py) and runs
         claude --permission-mode acceptEdits "/loop /ft8af-factory"
       in the main checkout. Edits are accepted, the commands the factory uses
       are allowlisted in .claude/settings.json, and anything else stops at a
       prompt (Patrick: no --dangerously-skip-permissions).

  Everything it decides goes to %LOCALAPPDATA%\ft8af-factory\autostart.log.

.PARAMETER DryRun
  Print the launch command instead of opening the tab.
#>
param(
    [string]$Repo = "C:\Users\burns\Projects\NEXT-FT8CN",
    [int]$WaitMinutes = 10,
    [switch]$DryRun
)

$ErrorActionPreference = "Continue"
$logDir = Join-Path $env:LOCALAPPDATA "ft8af-factory"
New-Item -ItemType Directory -Force $logDir | Out-Null
$log = Join-Path $logDir "autostart.log"
function Log([string]$msg) {
    $line = "{0:yyyy-MM-dd HH:mm:ss}  {1}" -f (Get-Date), $msg
    Add-Content -Path $log -Value $line -Encoding utf8
    Write-Output $line
}

Log "start (dry run: $DryRun)"

# 1. One factory per machine.
$running = Get-CimInstance Win32_Process -Filter "Name='claude.exe'" |
    Where-Object { $_.CommandLine -match '/ft8af-factory' }
if ($running) {
    Log "factory already running (pid $(@($running)[0].ProcessId)); nothing to do"
    if (-not $DryRun) { exit 0 }
    Log "dry run: carrying on to show the rest"
}

# 2. Network + GitHub login. Launch anyway after the wait: the loop retries
#    on its own and says what is wrong in its tab.
$deadline = (Get-Date).AddMinutes($WaitMinutes)
$ghOk = $false
while ((Get-Date) -lt $deadline) {
    $null = & gh api user --jq .login 2>$null
    if ($LASTEXITCODE -eq 0) { $ghOk = $true; break }
    Start-Sleep -Seconds 15
}
if ($ghOk) { Log "github reachable and logged in" }
else { Log "github still unreachable or logged out after $WaitMinutes min; launching anyway (run 'gh auth status')" }

$null = & wsl.exe -d Ubuntu --exec true 2>$null
Log "wsl nudge exit $LASTEXITCODE"

# 3. Newest dev in the main checkout, only when that is safe.
$branch = (& git -C $Repo rev-parse --abbrev-ref HEAD 2>$null)
$dirty = (& git -C $Repo status --porcelain 2>$null)
if ($branch -eq "dev" -and -not $dirty) {
    $null = & git -C $Repo fetch origin 2>$null
    $null = & git -C $Repo merge --ff-only origin/dev 2>$null
    Log "main checkout fast-forward exit $LASTEXITCODE (now $(& git -C $Repo rev-parse --short HEAD))"
} else {
    Log "main checkout on '$branch', dirty: $([bool]$dirty); left as is"
}

# 4. The tab. run-loop.cmd sets FT8AF_FACTORY=1 (the guard hook) and starts
#    claude; an env var set here would not reach a tab that an already-open
#    Windows Terminal spawns from its own environment.
$runLoop = Join-Path $Repo 'scripts\factory\run-loop.cmd'
# Start-Process joins these with spaces and quotes nothing, so anything with
# a space carries its own quotes.
$wtArgs = @('-w', 'new', 'new-tab', '--title', '"FT8AF factory"', '-d', "`"$Repo`"",
            'cmd.exe', '/k', "`"$runLoop`"")
$cmdLine = 'wt.exe ' + ($wtArgs -join ' ')
if ($DryRun) {
    Log "would run: $cmdLine"
    exit 0
}
Start-Process -FilePath 'wt.exe' -ArgumentList $wtArgs
Log "launched: $cmdLine"
