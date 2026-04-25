<#
.SYNOPSIS
  Stop the bench-webui process started by start-bench-tools.ps1.

.DESCRIPTION
  Mirror of scripts/stop-bench-tools.sh. Reads the PID file written
  by start-bench-tools.ps1 (default: $InstallDir\bench-webui.pid where
  $InstallDir = $HOME\ai-bench), verifies the PID still belongs to a
  live process, sends a graceful stop, and removes the PID file.

  Re-running is safe — a missing PID file or stale entry is reported
  and the script exits 0.

.EXAMPLE
  PS> .\scripts\stop-bench-tools.ps1
  PS> .\scripts\stop-bench-tools.ps1 -InstallDir D:\ai-bench
#>

[CmdletBinding()]
param(
    [string]$InstallDir = (Join-Path $HOME 'ai-bench')
)

$ErrorActionPreference = 'Stop'

function Write-Info([string]$msg) { Write-Host "[info] $msg" -ForegroundColor Cyan }
function Write-Ok  ([string]$msg) { Write-Host "[ok]   $msg" -ForegroundColor Green }
function Write-WarnLine([string]$msg) { Write-Host "[warn] $msg" -ForegroundColor Yellow }

$pidFile = Join-Path $InstallDir 'bench-webui.pid'

if (-not (Test-Path $pidFile)) {
    Write-Info "No PID file at $pidFile -- bench-webui is not tracked here. Nothing to stop."
    exit 0
}

$rawPid = (Get-Content -LiteralPath $pidFile -ErrorAction SilentlyContinue) -join ''
if ([string]::IsNullOrWhiteSpace($rawPid)) {
    Write-WarnLine "PID file $pidFile is empty -- removing."
    Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue
    exit 0
}

# Cast to int. Bail with a clear message instead of a Stop-induced exception
# if the file got corrupted (non-numeric content).
$webuiPid = 0
if (-not [int]::TryParse($rawPid.Trim(), [ref]$webuiPid)) {
    Write-WarnLine "PID file $pidFile contains non-numeric data ('$rawPid') -- removing."
    Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue
    exit 0
}

$proc = Get-Process -Id $webuiPid -ErrorAction SilentlyContinue
if (-not $proc) {
    Write-WarnLine "Process $webuiPid (from $pidFile) is not running -- removing stale PID file."
    Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue
    exit 0
}

Write-Info "Stopping bench-webui PID $webuiPid ..."
# Stop-Process is graceful where possible, escalates internally on Windows.
try {
    Stop-Process -Id $webuiPid -ErrorAction Stop
} catch {
    throw "Failed to stop PID $webuiPid : $($_.Exception.Message)"
}

# Wait up to 15s for the process to exit before declaring it stopped.
for ($i = 0; $i -lt 30; $i++) {
    if (-not (Get-Process -Id $webuiPid -ErrorAction SilentlyContinue)) {
        Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue
        Write-Ok "bench-webui (PID $webuiPid) stopped."
        exit 0
    }
    Start-Sleep -Milliseconds 500
}

Write-WarnLine "PID $webuiPid did not exit within 15s -- escalating to force-kill."
try {
    Stop-Process -Id $webuiPid -Force -ErrorAction Stop
} catch {
    throw "Force-kill of PID $webuiPid failed: $($_.Exception.Message)"
}
Start-Sleep -Seconds 1
if (Get-Process -Id $webuiPid -ErrorAction SilentlyContinue) {
    throw "PID $webuiPid still alive after force-kill. Investigate manually."
}
Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue
Write-Ok "bench-webui (PID $webuiPid) force-killed."
