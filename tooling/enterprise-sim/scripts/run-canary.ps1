<#
.SYNOPSIS
  Run a banking-app build through the enterprise-sim proxy + mirror, then audit.

.DESCRIPTION
  Verifies enterprise-sim is up, then invokes ./gradlew on the chosen target
  with system properties pointing at the local proxy + mirror. After the
  build, summarizes proxy.log + mirror.log to show every external destination
  the daemon contacted.

  Default target is :shared-domain:compileJava — exercises the full plugin
  resolution + Maven Central dependency graph for one canary subproject
  without compiling the 700-module banking-app.

.PARAMETER Target
  Gradle task to run. Default: :shared-domain:compileJava

.PARAMETER ProxyUser / ProxyPass
  Credentials the build presents to enterprise-sim's auth proxy. Must match
  --user / --pass passed when launching EnterpriseSim.java.

.EXAMPLE
  PS> .\tooling\enterprise-sim\scripts\run-canary.ps1
  PS> .\tooling\enterprise-sim\scripts\run-canary.ps1 -Target :shared-domain:test
#>
[CmdletBinding()]
param(
    [string]$Target = ":shared-domain:compileJava",
    [string]$ProxyUser = "bench-user",
    [string]$ProxyPass = "bench-pass",
    [int]$ProxyPort = 3128,
    [int]$MirrorPort = 8081
)

$ErrorActionPreference = 'Stop'
$repoRoot = Resolve-Path "$PSScriptRoot\..\..\.."

# --- 1. Probe sim ----------------------------------------------------------
try {
    $null = Invoke-WebRequest -Uri "http://localhost:$MirrorPort/health" -UseBasicParsing -TimeoutSec 2
    Write-Host "[canary] enterprise-sim is up (mirror :$MirrorPort)" -ForegroundColor Green
} catch {
    Write-Host "[canary] enterprise-sim is NOT running on port $MirrorPort." -ForegroundColor Red
    Write-Host "[canary] Start it in another terminal:" -ForegroundColor Yellow
    Write-Host "    java tooling/enterprise-sim/src/EnterpriseSim.java"
    exit 2
}

# --- 2. Compose system properties (READ by build, never written) -----------
$mirrorBase = "http://localhost:$MirrorPort"
$gradleArgs = @(
    "-Dhttps.proxyHost=localhost"
    "-Dhttps.proxyPort=$ProxyPort"
    "-Dhttps.proxyUser=$ProxyUser"
    "-Dhttps.proxyPassword=$ProxyPass"
    "-Dhttp.proxyHost=localhost"
    "-Dhttp.proxyPort=$ProxyPort"
    "-Dhttp.proxyUser=$ProxyUser"
    "-Dhttp.proxyPassword=$ProxyPass"
    # The pipe is a cmd.exe metacharacter — wrap the value in literal quotes
    # so gradlew.bat receives it as a single token and forwards intact to the JVM
    '"-Dhttp.nonProxyHosts=localhost|127.0.0.1"'
    # JDK 17 disallows Basic auth on HTTPS-via-proxy CONNECT by default; opt back in
    '-Djdk.http.auth.tunneling.disabledSchemes='
    "-Denterprise.sim.mirror=$mirrorBase"
    $Target
    "--info"
)

Write-Host "[canary] gradle target: $Target" -ForegroundColor Cyan
Write-Host "[canary] system props: https.proxy=localhost:$ProxyPort, mirror=$mirrorBase" -ForegroundColor Cyan
Write-Host ""

# --- 3. Run -----------------------------------------------------------------
Push-Location "$repoRoot/banking-app"
try {
    & "./gradlew.bat" @gradleArgs
    $exitCode = $LASTEXITCODE
} finally {
    Pop-Location
}

# --- 4. Audit ---------------------------------------------------------------
Write-Host ""
Write-Host "[canary] === audit summary ===" -ForegroundColor Cyan
$proxyLogPath = Join-Path $repoRoot "enterprise-sim-logs/proxy.log"
$mirrorLogPath = Join-Path $repoRoot "enterprise-sim-logs/mirror.log"

if (Test-Path $proxyLogPath) {
    $entries = Get-Content $proxyLogPath | Where-Object { $_ } | ForEach-Object { $_ | ConvertFrom-Json }
    Write-Host "[proxy] $($entries.Count) total request(s)"
    $byDest = $entries | Group-Object dest | Sort-Object Count -Descending
    foreach ($g in $byDest) {
        $authOks = ($g.Group | Where-Object { $_.authOk }).Count
        $denied  = $g.Count - $authOks
        Write-Host ("  {0,-40} {1,4} ok / {2,4} denied" -f $g.Name, $authOks, $denied)
    }
} else {
    Write-Host "[proxy] no log (proxy not contacted)"
}

if (Test-Path $mirrorLogPath) {
    $entries = Get-Content $mirrorLogPath | Where-Object { $_ } | ForEach-Object { $_ | ConvertFrom-Json }
    $hits = ($entries | Where-Object { $_.cacheHit }).Count
    $miss = ($entries | Where-Object { $_.upstreamFetched }).Count
    $err  = ($entries | Where-Object { $_.status -ne 200 }).Count
    Write-Host "[mirror] $($entries.Count) total request(s) - $hits cache-hits, $miss upstream-fetched, $err non-200"
    $byRepo = $entries | Group-Object repo
    foreach ($g in $byRepo) {
        Write-Host ("  {0,-20} {1,5} request(s)" -f $g.Name, $g.Count)
    }
} else {
    Write-Host "[mirror] no log (mirror not contacted)"
}

Write-Host ""
if ($exitCode -eq 0) {
    Write-Host "[canary] BUILD SUCCEEDED" -ForegroundColor Green
} else {
    Write-Host "[canary] BUILD FAILED (exit $exitCode)" -ForegroundColor Red
}
exit $exitCode
