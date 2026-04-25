<#
.SYNOPSIS
  Boot the pre-built ai-bench-java tools on Windows.

.DESCRIPTION
  Mirror of scripts/start-bench-tools.sh:
    1. Verifies a JDK 17-25 is on PATH.
    2. If $InstallDir (default $HOME\ai-bench) is missing the release
       artifacts, downloads them via 'gh release download'.
    3. Expands bench-cli if it's still in zip form.
    4. Launches bench-webui in the background, captures its PID +
       log path, prints the URL.
    5. Prints the bench-cli launcher path and a one-liner to add it
       to PATH for the current session.

  Re-running is safe: if bench-webui is already up (PID file matches a
  live process), the script reports that and exits without launching
  a duplicate. Stop with: Stop-Process -Id (Get-Content $InstallDir\bench-webui.pid)

.EXAMPLE
  PS> .\scripts\start-bench-tools.ps1
  PS> .\scripts\start-bench-tools.ps1 -InstallDir D:\ai-bench
#>

[CmdletBinding()]
param(
    [string]$InstallDir = (Join-Path $HOME 'ai-bench'),
    [string]$Repo = 'alfredsisley10/ai-bench-java'
)

$ErrorActionPreference = 'Stop'

function Write-Info([string]$msg) { Write-Host "[info] $msg" -ForegroundColor Cyan }
function Write-Ok  ([string]$msg) { Write-Host "[ok]   $msg" -ForegroundColor Green }
function Write-WarnLine([string]$msg) { Write-Host "[warn] $msg" -ForegroundColor Yellow }

# --- 1. JDK precheck --------------------------------------------------
if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    throw "java is not on PATH. Install JDK 17-25 (Temurin / OpenJDK / Corretto / Zulu) and re-run."
}
$verLine = (& java -version 2>&1 | Select-Object -First 1)
if ($verLine -match '"(\d+)') {
    $javaMajor = [int]$Matches[1]
    if ($javaMajor -lt 17 -or $javaMajor -gt 25) {
        throw "JDK $javaMajor found ($verLine); need 17-25."
    }
} else {
    throw "Could not parse 'java -version' output: $verLine"
}
Write-Ok "JDK $javaMajor detected ($verLine)"

# --- 2. Locate / download artifacts -----------------------------------
if (-not (Test-Path $InstallDir)) {
    New-Item -ItemType Directory -Path $InstallDir -Force | Out-Null
}
Set-Location $InstallDir

$cliZip   = Get-ChildItem -Filter 'bench-cli-*.zip'   -File -ErrorAction SilentlyContinue | Select-Object -First 1
$webuiJar = Get-ChildItem -Filter 'bench-webui-*.jar' -File -ErrorAction SilentlyContinue | Select-Object -First 1

if (-not $cliZip -or -not $webuiJar) {
    if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
        throw @"
$InstallDir is missing one or both release artifacts and 'gh' is not on PATH.
Install gh (https://cli.github.com) OR download manually from
https://github.com/$Repo/releases/latest into $InstallDir, then re-run.
"@
    }
    # gh release download without an explicit tag defaults to "latest
    # STABLE", which excludes prereleases. Resolve the most recent
    # release tag (prereleases included) first, then download by tag.
    Write-Info "Resolving most recent release tag from $Repo..."
    $latestTag = & gh release list --repo $Repo --limit 1 --json tagName -q '.[0].tagName' 2>$null
    if (-not $latestTag) { throw "No releases published in $Repo." }
    Write-Info "Downloading $latestTag from $Repo into $InstallDir..."
    & gh release download $latestTag --repo $Repo `
        --pattern 'bench-cli-*.zip' --pattern 'bench-webui-*.jar' --clobber
    if ($LASTEXITCODE -ne 0) { throw "gh release download exited with code $LASTEXITCODE" }
    $cliZip   = Get-ChildItem -Filter 'bench-cli-*.zip'   -File | Select-Object -First 1
    $webuiJar = Get-ChildItem -Filter 'bench-webui-*.jar' -File | Select-Object -First 1
    if (-not $cliZip)   { throw "bench-cli zip still missing after gh release download." }
    if (-not $webuiJar) { throw "bench-webui jar still missing after gh release download." }
    Write-Ok "Downloaded $($cliZip.Name) + $($webuiJar.Name)"
} else {
    Write-Ok "Found existing artifacts: $($cliZip.Name) + $($webuiJar.Name)"
}

# --- 3. Expand bench-cli if needed ------------------------------------
$cliDir = Get-ChildItem -Directory -Filter 'bench-cli-*' -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $cliDir) {
    Write-Info "Expanding $($cliZip.Name)..."
    Expand-Archive -Path $cliZip.FullName -DestinationPath $InstallDir -Force
    $cliDir = Get-ChildItem -Directory -Filter 'bench-cli-*' | Select-Object -First 1
    if (-not $cliDir) { throw "Expand-Archive did not produce a bench-cli-* directory." }
}
$cliBat = Join-Path $cliDir.FullName 'bin\bench-cli.bat'
if (-not (Test-Path $cliBat)) { throw "Expected bench-cli launcher at $cliBat but it does not exist." }

# --- 4. Launch bench-webui --------------------------------------------
$logFile = Join-Path $InstallDir 'bench-webui.log'
$errFile = Join-Path $InstallDir 'bench-webui.err'
$pidFile = Join-Path $InstallDir 'bench-webui.pid'

$alreadyRunning = $false
if (Test-Path $pidFile) {
    $existingPid = [int](Get-Content $pidFile -ErrorAction SilentlyContinue)
    if ($existingPid -and (Get-Process -Id $existingPid -ErrorAction SilentlyContinue)) {
        $alreadyRunning = $true
        Write-WarnLine "bench-webui is already running (PID $existingPid)."
        Write-Info "  Logs:    $logFile"
        Write-Info "  Web UI:  http://localhost:7777"
        Write-Info "  Stop:    Stop-Process -Id $existingPid"
    } else {
        Remove-Item $pidFile -Force -ErrorAction SilentlyContinue
    }
}

if (-not $alreadyRunning) {
    Write-Info "Starting bench-webui ..."
    $proc = Start-Process -FilePath java `
        -ArgumentList @('-jar', $webuiJar.FullName) `
        -RedirectStandardOutput $logFile `
        -RedirectStandardError  $errFile `
        -WindowStyle Hidden `
        -PassThru
    $proc.Id | Set-Content -Path $pidFile

    Start-Sleep -Seconds 2
    if (-not (Get-Process -Id $proc.Id -ErrorAction SilentlyContinue)) {
        Remove-Item $pidFile -Force -ErrorAction SilentlyContinue
        $tail = if (Test-Path $errFile) { Get-Content $errFile -Tail 20 } else { '' }
        if (-not $tail -and (Test-Path $logFile)) { $tail = Get-Content $logFile -Tail 20 }
        throw "bench-webui exited within 2 seconds. Last lines of log/err:`n$($tail -join "`n")"
    }
    Write-Ok "bench-webui PID $($proc.Id)"
    Write-Info "  Logs:    $logFile"
    Write-Info "  Web UI:  http://localhost:7777  (Spring Boot may take ~10-30s to finish booting)"
    Write-Info "  Stop:    Stop-Process -Id $($proc.Id)"
}

# --- 5. bench-cli pointer --------------------------------------------
Write-Host ""
Write-Info "bench-cli launcher: $cliBat"
Write-Info "Add to PATH for this session:  `$env:Path += ';$($cliDir.FullName)\bin'"
Write-Info "Run:                            bench-cli --help"
