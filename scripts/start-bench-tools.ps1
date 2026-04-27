<#
.SYNOPSIS
  Boot the pre-built ai-bench-java tools on Windows.

.DESCRIPTION
  Mirror of scripts/start-bench-tools.sh:
    1. Verifies a JDK 17-25 is on PATH.
    2. If $InstallDir (default $HOME\ai-bench) is missing the release
       artifacts, copies them from the repo's dist/ folder when
       present, otherwise fetches them from the GitHub Releases API
       via Invoke-WebRequest (no 'gh' CLI dependency).
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
# `java -version` writes to STDERR (Java quirk). The natural-looking
# `& java -version 2>&1 | Select-Object -First 1` fails under
# $ErrorActionPreference = 'Stop' because PowerShell turns each merged
# stderr line into an ErrorRecord and terminates. Use the .NET Process
# API to read stderr directly, bypassing the merge.
$javaPath = (Get-Command java).Source
$javaPsi  = New-Object System.Diagnostics.ProcessStartInfo
$javaPsi.FileName               = $javaPath
$javaPsi.Arguments              = '-version'
$javaPsi.RedirectStandardOutput = $true
$javaPsi.RedirectStandardError  = $true
$javaPsi.UseShellExecute        = $false
$javaPsi.CreateNoWindow         = $true
$javaProc = [System.Diagnostics.Process]::Start($javaPsi)
$javaOut  = $javaProc.StandardError.ReadToEnd() + $javaProc.StandardOutput.ReadToEnd()
$javaProc.WaitForExit()
$verLine = ($javaOut -split "`r?`n" | Where-Object { $_ } | Select-Object -First 1)
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

# Cache lookup: if the script lives inside a repo checkout that ships
# pre-built artifacts under dist/, copy them into $InstallDir rather
# than downloading. This makes 'git clone' alone sufficient for
# enterprise users who can reach the git remote but not GitHub Releases.
#
# Staleness check: if the LOCAL artifacts already exist but the dist/
# copy is NEWER (LastWriteTimeUtc), overwrite them. Without this, an
# operator who git-pulled a newer dist/jar but already had a previous
# run's jar in $InstallDir would silently keep running the older
# binary -- the class of bug that masked the /llm 500 fix on Windows
# machines that had been booted from an earlier release.
$repoDist = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\dist') -ErrorAction SilentlyContinue)
if ($repoDist -and (Test-Path $repoDist)) {
    $repoCli   = Get-ChildItem -LiteralPath $repoDist -Filter 'bench-cli-*.zip'   -File -ErrorAction SilentlyContinue | Select-Object -First 1
    $repoWebui = Get-ChildItem -LiteralPath $repoDist -Filter 'bench-webui-*.jar' -File -ErrorAction SilentlyContinue | Select-Object -First 1
    # Size + mtime is more reliable than mtime alone: a Windows machine
    # that booted from an older release jar, then later git-pulled a
    # newer dist/jar, can end up with matching mtimes (Copy-Item
    # preserves nothing by default; git can preserve commit time
    # depending on core.checkstat). Size catches the content-change
    # case the mtime check would miss.
    function Test-NeedsRefresh($src, $dst) {
        if ($null -eq $dst) { return $true }
        if ($src.Length -ne $dst.Length) { return $true }
        return $src.LastWriteTimeUtc -gt $dst.LastWriteTimeUtc
    }

    if ($repoCli -and $repoWebui) {
        $refreshedCli = $false
        $refreshedWebui = $false
        if (Test-NeedsRefresh $repoCli $cliZip) {
            # Wipe any unzipped bench-cli-* dir so the next "Expand"
            # step recreates it from the fresher zip.
            Get-ChildItem -LiteralPath $InstallDir -Directory -Filter 'bench-cli-*' -ErrorAction SilentlyContinue |
                ForEach-Object { Remove-Item -LiteralPath $_.FullName -Recurse -Force -ErrorAction SilentlyContinue }
            Copy-Item -LiteralPath $repoCli.FullName -Destination $InstallDir -Force
            $refreshedCli = $true
        }
        if (Test-NeedsRefresh $repoWebui $webuiJar) {
            Copy-Item -LiteralPath $repoWebui.FullName -Destination $InstallDir -Force
            $refreshedWebui = $true
        }
        if ($refreshedCli -or $refreshedWebui) {
            $cliZip   = Get-ChildItem -Filter 'bench-cli-*.zip'   -File | Select-Object -First 1
            $webuiJar = Get-ChildItem -Filter 'bench-webui-*.jar' -File | Select-Object -First 1
            Write-Ok "Refreshed from $repoDist (cli=$refreshedCli, webui=$refreshedWebui)."
        }
    }
}

if (-not $cliZip -or -not $webuiJar) {
    # Use built-in cmdlets (Invoke-RestMethod / Invoke-WebRequest) so this
    # script depends only on PowerShell 5.1+. /releases?per_page=1 returns
    # the most recent release including prereleases; /releases/latest would
    # skip prereleases and is therefore avoided.
    try {
        [Net.ServicePointManager]::SecurityProtocol =
            [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
    } catch { }

    # IWR progress bar is 5-10x slower on PS 5.1; suppress it.
    $prevProgress = $ProgressPreference
    $ProgressPreference = 'SilentlyContinue'

    $apiUrl  = "https://api.github.com/repos/$Repo/releases?per_page=1"
    $headers = @{ 'User-Agent' = 'ai-bench-java-startup' }

    Write-Info "Resolving most recent release via $apiUrl ..."
    try {
        $releases = Invoke-RestMethod -Uri $apiUrl -Headers $headers -UseBasicParsing
    } catch {
        $ProgressPreference = $prevProgress
        throw "GitHub API request failed: $($_.Exception.Message)"
    }
    if (-not $releases -or $releases.Count -lt 1) {
        $ProgressPreference = $prevProgress
        throw "No releases found at $apiUrl."
    }
    $release = $releases[0]
    $tag     = $release.tag_name
    Write-Info "Latest release: $tag -- downloading assets..."

    foreach ($pat in @('bench-cli-*.zip', 'bench-webui-*.jar')) {
        $asset = $release.assets | Where-Object { $_.name -like $pat } | Select-Object -First 1
        if (-not $asset) {
            $ProgressPreference = $prevProgress
            throw "Release $tag has no asset matching '$pat'."
        }
        $dest = Join-Path $InstallDir $asset.name
        $sizeMB = [math]::Round($asset.size / 1MB, 1)
        Write-Info "  $($asset.name) ($sizeMB MB)"
        try {
            Invoke-WebRequest -Uri $asset.browser_download_url -Headers $headers `
                -OutFile $dest -UseBasicParsing
        } catch {
            $ProgressPreference = $prevProgress
            throw "Download failed: $($asset.browser_download_url) -- $($_.Exception.Message)"
        }
    }
    $ProgressPreference = $prevProgress

    $cliZip   = Get-ChildItem -Filter 'bench-cli-*.zip'   -File | Select-Object -First 1
    $webuiJar = Get-ChildItem -Filter 'bench-webui-*.jar' -File | Select-Object -First 1
    if (-not $cliZip)   { throw "bench-cli zip still missing after download." }
    if (-not $webuiJar) { throw "bench-webui jar still missing after download." }
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
    # -WindowStyle Hidden is supported on Windows only. PowerShell 7+
    # exposes $IsWindows; Windows PowerShell 5.1 (PSEdition=Desktop)
    # always runs on Windows. Either signal is enough.
    $onWindows = ($PSVersionTable.PSEdition -eq 'Desktop') -or $IsWindows
    $startArgs = @{
        FilePath               = 'java'
        ArgumentList           = @('-jar', $webuiJar.FullName)
        RedirectStandardOutput = $logFile
        RedirectStandardError  = $errFile
        PassThru               = $true
    }
    if ($onWindows) { $startArgs.WindowStyle = 'Hidden' }
    $proc = Start-Process @startArgs
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
