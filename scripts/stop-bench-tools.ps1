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

# Snapshot descendants BEFORE we kill the parent — once the parent
# exits, ProcessId/ParentProcessId rows for it disappear from the OS
# tables and we can't reliably enumerate orphans afterward.
# bench-webui's BankingAppManager spawns banking-app as a child JVM on
# port 8080; without explicit tree-kill those children hold file
# handles in $InstallDir, which is why `Remove-Item $HOME\ai-bench`
# was failing for the user even after the stop script claimed success.
function Get-DescendantPids([int]$rootPid) {
    $all = Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
        Select-Object ProcessId, ParentProcessId
    $found = New-Object System.Collections.Generic.HashSet[int]
    $queue = New-Object System.Collections.Queue
    $queue.Enqueue($rootPid)
    while ($queue.Count -gt 0) {
        $cur = $queue.Dequeue()
        $children = $all | Where-Object { $_.ParentProcessId -eq $cur }
        foreach ($c in $children) {
            if ($found.Add([int]$c.ProcessId)) {
                $queue.Enqueue([int]$c.ProcessId)
            }
        }
    }
    return $found
}

$descendants = Get-DescendantPids $webuiPid
if ($descendants.Count -gt 0) {
    Write-Info "Tree under PID $webuiPid : $($descendants -join ', ')"
}

Write-Info "Stopping bench-webui PID $webuiPid (graceful) ..."
try {
    Stop-Process -Id $webuiPid -ErrorAction Stop
} catch {
    throw "Failed to stop PID $webuiPid : $($_.Exception.Message)"
}

# Wait up to 15s for the parent to exit.
$exited = $false
for ($i = 0; $i -lt 30; $i++) {
    if (-not (Get-Process -Id $webuiPid -ErrorAction SilentlyContinue)) {
        $exited = $true; break
    }
    Start-Sleep -Milliseconds 500
}

if (-not $exited) {
    Write-WarnLine "PID $webuiPid did not exit within 15s -- escalating to taskkill /T /F (tree)."
    & taskkill /PID $webuiPid /T /F | Out-Null
    Start-Sleep -Seconds 1
    if (Get-Process -Id $webuiPid -ErrorAction SilentlyContinue) {
        throw "PID $webuiPid still alive after taskkill /T /F. Investigate manually."
    }
}

# Reap any descendants the parent didn't already terminate. Stop-Process
# on a Java process under cmd.exe can leave child JVMs alive; this is
# the difference between "stop succeeded" and "$InstallDir is deletable".
foreach ($d in $descendants) {
    if (Get-Process -Id $d -ErrorAction SilentlyContinue) {
        Write-Info "Reaping leftover descendant PID $d"
        & taskkill /PID $d /T /F 2>$null | Out-Null
    }
}

# Brief pause: Windows may take a moment to release file handles after
# the process tree exits. Without this, an immediate Remove-Item on
# $InstallDir can still fail with "in use by another process".
Start-Sleep -Milliseconds 500

Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue
Write-Ok "bench-webui (PID $webuiPid) stopped."
if ($descendants.Count -gt 0) {
    Write-Info "Reaped $($descendants.Count) descendant process(es). $InstallDir should now be removable."
}
