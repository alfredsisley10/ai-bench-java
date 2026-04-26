<#
.SYNOPSIS
  Build every sub-project from source, in dependency order.

.DESCRIPTION
  Mirror of scripts/build-source.sh. Replaces the manual sequence:
    cd banking-app    ; .\gradlew.bat build
    cd ..\bench-harness; .\gradlew.bat build
    cd ..\bench-cli   ; .\gradlew.bat build
    cd ..\bench-webui ; .\gradlew.bat build

.EXAMPLE
  PS> .\scripts\build-source.ps1
  PS> .\scripts\build-source.ps1 -Skip banking-app
  PS> .\scripts\build-source.ps1 -- clean test
#>

[CmdletBinding()]
param(
    [string]$Skip = '',
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$RemainingArgs = @()
)

$ErrorActionPreference = 'Stop'

$projects = @('banking-app', 'bench-harness', 'bench-cli', 'bench-webui')
$skipped  = @($Skip -split ',' | Where-Object { $_ })

# Strip any leading '--' Bash-style separator users might pass through.
if ($RemainingArgs.Count -gt 0 -and $RemainingArgs[0] -eq '--') {
    $RemainingArgs = $RemainingArgs[1..($RemainingArgs.Count - 1)]
}
$gradleArgs = if ($RemainingArgs.Count -eq 0) { @('build') } else { $RemainingArgs }

if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    throw "java not on PATH -- install JDK 17-25"
}

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot '..')
foreach ($proj in $projects) {
    if ($skipped -contains $proj) { Write-Host "==> skip $proj" -ForegroundColor Cyan; continue }

    $gradlew = Join-Path $repoRoot "$proj\gradlew.bat"
    if (-not (Test-Path $gradlew)) { throw "$proj\gradlew.bat missing" }

    Write-Host "==> $proj :: .\gradlew.bat $($gradleArgs -join ' ')" -ForegroundColor Cyan
    Push-Location (Join-Path $repoRoot $proj)
    try {
        & $gradlew @gradleArgs
        if ($LASTEXITCODE -ne 0) { throw "$proj build failed (exit $LASTEXITCODE)" }
        Write-Host "OK $proj" -ForegroundColor Green
    } finally {
        Pop-Location
    }
}

Write-Host ""
Write-Host "OK All builds completed." -ForegroundColor Green
