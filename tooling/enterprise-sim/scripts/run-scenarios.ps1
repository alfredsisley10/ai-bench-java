<#
.SYNOPSIS
  Run the enterprise-build failure-scenario catalog and capture errors.

.DESCRIPTION
  For each scenario, restarts enterprise-sim with the appropriate fault flags
  (or the user's build with broken config), runs ./gradlew :shared-domain:compileJava,
  and captures stdout + stderr + a snapshot of proxy.log/mirror.log into
  enterprise-sim-logs/scenarios/<scenario>/.

  Scenarios are intentionally fast-failing — each should fail-or-misbehave within
  20-40 seconds.

  Pass -Scenario <name> to run a single scenario.

.EXAMPLE
  PS> .\run-scenarios.ps1                            # run all scenarios
  PS> .\run-scenarios.ps1 -Scenario mirror-down      # run just one
  PS> .\run-scenarios.ps1 -List                      # list scenario names
#>
[CmdletBinding()]
param(
    [string]$Scenario,
    [switch]$List
)

$ErrorActionPreference = 'Stop'
$repoRoot   = (Resolve-Path "$PSScriptRoot\..\..\..").Path
$javaExe    = "C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot\bin\java.exe"
$simSrc     = "$repoRoot\tooling\enterprise-sim\src\EnterpriseSim.java"
$outRoot    = "$repoRoot\enterprise-sim-logs\scenarios"
$proxyLog   = "$repoRoot\enterprise-sim-logs\proxy.log"
$mirrorLog  = "$repoRoot\enterprise-sim-logs\mirror.log"

# --- scenario definitions ---
# Each scenario is a hashtable:
#   name        - directory-safe id
#   describe    - one-line human description
#   simFlags    - list of EnterpriseSim flags (or @() to use defaults)
#   buildFlags  - extra -D flags / overrides for the gradle invocation; this list
#                 is built ON TOP of the canonical-correct base. To OMIT a base
#                 flag, add an entry with the prefix "OMIT:" e.g. "OMIT:-Denterprise.sim.mirror"
$scenarios = @(
    @{
        name = "00-baseline-correct"
        describe = "Sanity: correct config should succeed past plugin/dep resolution"
        simFlags = @()
        buildFlags = @()
    }
    @{
        name = "01-mirror-down"
        describe = "Sim mirror returns 503 to all requests"
        simFlags = @("--mirror-down")
        buildFlags = @()
    }
    @{
        name = "02-wrong-proxy-password"
        describe = "Build sends bad password to proxy"
        simFlags = @()
        buildFlags = @("-Dhttps.proxyPassword=NOPE", "-Dhttp.proxyPassword=NOPE")
    }
    @{
        name = "03-forgot-mirror-property"
        describe = "User forgot to pass -Denterprise.sim.mirror; build talks direct to maven.org via proxy (slow, leaks repo names to proxy log)"
        simFlags = @()
        buildFlags = @("OMIT:-Denterprise.sim.mirror")
    }
    @{
        name = "04-typo-nonproxyhosts"
        describe = "User mistyped 'nonProxyHosts' as 'noProxyHosts'; mirror traffic routes through proxy"
        simFlags = @()
        buildFlags = @('OMIT:"-Dhttp.nonProxyHosts=localhost|127.0.0.1"',
                       '"-Dhttp.noProxyHosts=localhost|127.0.0.1"')
    }
    @{
        name = "05-foojay-blocked"
        describe = "Corporate proxy blocks api.foojay.io; toolchain auto-download fails"
        simFlags = @("--proxy-blackhole=api.foojay.io")
        buildFlags = @()
    }
    @{
        name = "06-mirror-missing-plugin"
        describe = "Mirror returns 404 for any spring-boot plugin path; plugin resolution fails"
        simFlags = @("--mirror-404=.*spring-boot.*")
        buildFlags = @()
    }
)

if ($List) {
    Write-Host "Available scenarios:"
    $scenarios | ForEach-Object { Write-Host ("  {0,-30} {1}" -f $_.name, $_.describe) }
    exit 0
}

# --- canonical base build args (correct enterprise-sim setup) ---
$baseBuildArgs = @(
    "-Dhttps.proxyHost=localhost"
    "-Dhttps.proxyPort=3128"
    "-Dhttps.proxyUser=bench-user"
    "-Dhttps.proxyPassword=bench-pass"
    "-Dhttp.proxyHost=localhost"
    "-Dhttp.proxyPort=3128"
    "-Dhttp.proxyUser=bench-user"
    "-Dhttp.proxyPassword=bench-pass"
    '"-Dhttp.nonProxyHosts=localhost|127.0.0.1"'
    '-Djdk.http.auth.tunneling.disabledSchemes='
    "-Denterprise.sim.mirror=http://localhost:8081"
    ":shared-domain:compileJava"
    "--refresh-dependencies"
    "--info"
)

function Stop-Sim {
    # Find pids by listening port — sim is the only thing on 3128/8081 in this setup
    $pids = Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
        Where-Object { $_.LocalPort -in 3128, 8081 } |
        Select-Object -ExpandProperty OwningProcess -Unique
    foreach ($id in $pids) {
        Stop-Process -Id $id -Force -ErrorAction SilentlyContinue
    }
    Start-Sleep -Milliseconds 500
}

function Start-Sim([string[]]$flags) {
    $argList = @($simSrc) + $flags
    $proc = Start-Process -FilePath $javaExe -ArgumentList $argList `
        -PassThru -WindowStyle Hidden `
        -WorkingDirectory $repoRoot `
        -RedirectStandardOutput "$repoRoot\enterprise-sim-logs\sim.stdout" `
        -RedirectStandardError "$repoRoot\enterprise-sim-logs\sim.stderr"
    # wait for health
    $deadline = (Get-Date).AddSeconds(30)
    while ((Get-Date) -lt $deadline) {
        try { $null = Invoke-WebRequest -Uri "http://localhost:8081/health" -UseBasicParsing -TimeoutSec 1; return $proc } catch { Start-Sleep -Milliseconds 500 }
    }
    throw "sim failed to come up within 30s"
}

function Snapshot-Logs([string]$destDir) {
    # Get-Content/Set-Content works even if the sim has the source open (append-shared)
    if (Test-Path $proxyLog)  { Get-Content $proxyLog  | Set-Content "$destDir\proxy.log"  -Encoding utf8 -ErrorAction SilentlyContinue }
    if (Test-Path $mirrorLog) { Get-Content $mirrorLog | Set-Content "$destDir\mirror.log" -Encoding utf8 -ErrorAction SilentlyContinue }
}

function Truncate-Logs {
    if (Test-Path $proxyLog)  { Clear-Content $proxyLog  -ErrorAction SilentlyContinue }
    if (Test-Path $mirrorLog) { Clear-Content $mirrorLog -ErrorAction SilentlyContinue }
}

# --- main loop ---
$selectedScenarios = if ($Scenario) {
    $scenarios | Where-Object { $_.name -eq $Scenario }
} else {
    $scenarios
}
if (-not $selectedScenarios) { Write-Host "No matching scenario."; exit 1 }

New-Item -ItemType Directory -Path $outRoot -Force | Out-Null
$results = @()

foreach ($s in $selectedScenarios) {
    Write-Host ""
    Write-Host "======== Scenario: $($s.name) ========" -ForegroundColor Cyan
    Write-Host "  $($s.describe)" -ForegroundColor Cyan
    $scenarioOut = Join-Path $outRoot $s.name
    New-Item -ItemType Directory -Path $scenarioOut -Force | Out-Null

    # Compose effective build args: base minus omits, plus extras
    $omits = $s.buildFlags | Where-Object { $_ -like "OMIT:*" } | ForEach-Object { $_.Substring(5) }
    $extras = $s.buildFlags | Where-Object { $_ -notlike "OMIT:*" }
    $effective = @()
    foreach ($a in $baseBuildArgs) {
        $omit = $false
        foreach ($o in $omits) { if ($a -like "$o*" -or $a -eq $o) { $omit = $true; break } }
        if (-not $omit) { $effective += $a }
    }
    $effective += $extras

    Write-Host "  effective gradle args: $($effective -join ' ')" -ForegroundColor DarkGray

    # Restart sim with this scenario's flags
    Stop-Sim
    Truncate-Logs
    $proc = Start-Sim $s.simFlags

    # Run gradle via Start-Process — keeps stdout/stderr separate and avoids PS 5.1's
    # NativeCommandError wrapping of stderr lines (which otherwise trips $ErrorActionPreference='Stop')
    $stdout = "$scenarioOut\stdout.txt"
    $stderr = "$scenarioOut\stderr.txt"
    $proc = Start-Process -FilePath "$repoRoot\banking-app\gradlew.bat" `
        -ArgumentList $effective `
        -WorkingDirectory "$repoRoot\banking-app" `
        -NoNewWindow -Wait -PassThru `
        -RedirectStandardOutput $stdout `
        -RedirectStandardError  $stderr
    $code = $proc.ExitCode
    Snapshot-Logs $scenarioOut

    # Tail the captured output for the at-a-glance summary
    $tail = if (Test-Path $stdout) { Get-Content $stdout -Tail 25 -ErrorAction SilentlyContinue } else { @() }
    $results += [pscustomobject]@{
        Scenario = $s.name
        ExitCode = $code
        ProxyLines = if (Test-Path "$scenarioOut\proxy.log") { (Get-Content "$scenarioOut\proxy.log").Count } else { 0 }
        MirrorLines = if (Test-Path "$scenarioOut\mirror.log") { (Get-Content "$scenarioOut\mirror.log").Count } else { 0 }
        OutputDir = $scenarioOut
    }
    $color = if ($code -eq 0) { 'Green' } else { 'Yellow' }
    Write-Host "  exit code: $code  proxy-lines: $($results[-1].ProxyLines)  mirror-lines: $($results[-1].MirrorLines)" -ForegroundColor $color
}

# Cleanup: leave sim up in default state for further work
Stop-Sim
$null = Start-Sim @()

Write-Host ""
Write-Host "==== SUMMARY ====" -ForegroundColor Cyan
$results | Format-Table -AutoSize
Write-Host "Per-scenario outputs in: $outRoot" -ForegroundColor Cyan
