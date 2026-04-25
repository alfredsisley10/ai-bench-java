<#
.SYNOPSIS
  Pre-build environment check for ai-bench-java on Windows.

.DESCRIPTION
  Walks every prerequisite a fresh Windows build needs -- JDK, network
  reachability to the artifact repos Gradle hits at build time, proxy
  configuration, corporate-CA TLS interception, disk space -- and prints
  one green / red line per check with a remediation hint when something
  fails. Exits non-zero if any required check fails.

  Designed to be safe to re-run; performs no installs or writes.

.EXAMPLE
  PS> .\scripts\build-health-check.ps1
  PS> .\scripts\build-health-check.ps1 -Detailed
#>

[CmdletBinding()]
param(
    [switch]$Detailed,
    # Skip every interactive prompt (non-zero exit if a prompt would have
    # fired). For CI / unattended use.
    [switch]$NonInteractive,
    # Point at an existing Maven settings.xml, maven-wrapper.properties,
    # OR a directory to recursively search for those files (up to depth
    # 3). Useful when IT has dropped the file somewhere non-standard.
    [string]$MavenConfigPath
)

$ErrorActionPreference = 'Continue'
$script:Failures = @()
$script:Warnings = @()

# Resolve paths early so every check below can reference them safely.
# Historical bug: $gradleProps was first referenced in the Proxy section
# but defined mid-way through that same section, producing blank [INFO]
# lines on the first pass and would crash under StrictMode. Defining it
# here (before the first Write-Section) makes every later check safe.
$script:RepoRoot = if ($PSScriptRoot) {
    (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
} else {
    (Get-Location).Path
}
$gradleProps    = Join-Path $env:USERPROFILE '.gradle\gradle.properties'
$gradleUserHome = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $env:USERPROFILE '.gradle' }

function Write-Pass([string]$msg) { Write-Host "  [PASS] $msg" -ForegroundColor Green }
function Write-Fail([string]$msg, [string]$hint = '') {
    Write-Host "  [FAIL] $msg" -ForegroundColor Red
    if ($hint) { Write-Host "         -> $hint" -ForegroundColor Yellow }
    $script:Failures += $msg
}
function Write-Warn([string]$msg, [string]$hint = '') {
    Write-Host "  [WARN] $msg" -ForegroundColor Yellow
    if ($hint) { Write-Host "         -> $hint" -ForegroundColor Yellow }
    $script:Warnings += $msg
}
function Write-Info([string]$msg) { Write-Host "  [INFO] $msg" -ForegroundColor Cyan }
function Write-Section([string]$title) {
    Write-Host ""
    Write-Host "== $title ==" -ForegroundColor White
}

# ---------------------------------------------------------------------------
# JDK
# ---------------------------------------------------------------------------
Write-Section "JDK 17-25"

$javaCmd = Get-Command java -ErrorAction SilentlyContinue
if (-not $javaCmd) {
    Write-Fail "java is not on PATH" `
        "Install JDK 21 (Temurin / OpenJDK / Corretto / Zulu) and add its bin\ to PATH."
} else {
    $verLine = (& java -version 2>&1 | Select-Object -First 1)
    if ($verLine -match '"(\d+)') {
        $major = [int]$Matches[1]
        if ($major -ge 17 -and $major -le 25) {
            Write-Pass "java -version reports JDK $major  ($verLine)"
        } elseif ($major -gt 25) {
            Write-Warn "JDK $major is newer than the tested range (17-25); Gradle 9.4.1 may not support it. Consider using a JDK between 17 and 25."
        } else {
            Write-Fail "JDK $major found; this project needs 17 through 25" `
                "Install JDK 17-25 (OpenJDK / Oracle / Temurin / Corretto / Zulu); set JAVA_HOME to that install."
        }
    } else {
        Write-Warn "java -version produced unexpected output: $verLine"
    }
}

if (-not $env:JAVA_HOME) {
    Write-Warn "JAVA_HOME is not set"
    # If we found a JDK on PATH, offer to derive JAVA_HOME from it and
    # set it -- either for this session or permanently in the user env.
    # Try methods in order of reliability:
    #   1. `java -XshowSettings:properties` -- the JRE itself reports
    #      java.home; works through ANY shim (Chocolatey, winget,
    #      WindowsApps) because it's resolved internally by the JVM.
    #   2. Resolve the shim path via Split-Path; good for direct installs.
    if ($javaCmd -and -not $NonInteractive) {
        $jdkHome = $null

        # Method 1: ask Java.
        try {
            $jvmProps = & java -XshowSettings:properties -version 2>&1
            $homeLine = $jvmProps | Select-String -Pattern '^\s*java\.home\s*=\s*(.+)$' | Select-Object -First 1
            if ($homeLine) {
                $candidate = $homeLine.Matches[0].Groups[1].Value.Trim()
                if (Test-Path (Join-Path $candidate 'bin\java.exe')) {
                    $jdkHome = $candidate
                    Write-Info "Detected JDK install root via java.home property: $jdkHome"
                }
            }
        } catch { }

        # Method 2 (fallback): path walk from the shim.
        if (-not $jdkHome) {
            $javaExe = $javaCmd.Path
            $resolved = (Resolve-Path $javaExe -ErrorAction SilentlyContinue).ProviderPath
            if (-not $resolved) { $resolved = $javaExe }
            $maybeHome = Split-Path (Split-Path $resolved)
            if (Test-Path (Join-Path $maybeHome 'bin\java.exe')) {
                $jdkHome = $maybeHome
                Write-Info "Detected JDK install root via path walk: $jdkHome"
            }
        }

        if ($jdkHome) {
            Write-Host "  Set JAVA_HOME to this location?" -ForegroundColor Yellow
            Write-Host "    [p] Permanent  -- writes to your user Environment Variables; every new terminal inherits"
            Write-Host "    [y] Session    -- only affects this PowerShell window"
            Write-Host "    [s] Skip       -- set it yourself later"
            $resp = Read-Host "  Choose [p/y/s]"
            switch -regex ($resp) {
                '^p' {
                    [Environment]::SetEnvironmentVariable('JAVA_HOME', $jdkHome, 'User')
                    $env:JAVA_HOME = $jdkHome
                    Write-Pass "JAVA_HOME set to $jdkHome (permanent, user scope)."
                    Write-Info "Open a NEW terminal for other tools to see it; current terminal is already set."
                }
                '^y' {
                    $env:JAVA_HOME = $jdkHome
                    Write-Pass "JAVA_HOME set to $jdkHome for this session only."
                    Write-Info "To persist, re-run and choose [p], or set in System > Environment Variables."
                }
                default {
                    Write-Info "Skipped. Set JAVA_HOME manually before running gradle."
                }
            }
        } else {
            # Both methods failed -- spell out exactly what the user needs to do.
            Write-Warn "Could not derive the JDK install root automatically." `
                "Your 'java' command is likely a shim (Chocolatey / winget / WindowsApps) that doesn't sit in a standard <jdk>/bin/ layout."
            Write-Info "Run this to find the real JDK location, then set JAVA_HOME to the path after 'java.home =':"
            Write-Info "  java -XshowSettings:properties -version 2>&1 | Select-String 'java.home'"
            Write-Info "Example output:"
            Write-Info "    java.home = C:\Program Files\Eclipse Adoptium\jdk-25.0.1.9-hotspot"
            Write-Info "Then set it permanently (no admin needed):"
            Write-Info "  [Environment]::SetEnvironmentVariable('JAVA_HOME', 'C:\Program Files\...\jdk-25.0.1.9-hotspot', 'User')"
            Write-Info "Open a NEW terminal after that -- env-var changes don't apply to already-open shells."
        }
    }
} elseif (-not (Test-Path $env:JAVA_HOME)) {
    Write-Fail "JAVA_HOME points at a missing path: $env:JAVA_HOME"
} else {
    Write-Pass "JAVA_HOME = $env:JAVA_HOME"
}

# Full-JDK check: Gradle needs javac + keytool, not just java. A JRE-only
# install passes the `java` check above but fails at compile time with
# opaque "tool not found" errors. On Windows the JRE and JDK have nearly
# identical names in Adoptium / Eclipse Foundation — users frequently
# install one when they needed the other.
if ($javaCmd) {
    $javacPath = $null
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\javac.exe'))) {
        $javacPath = Join-Path $env:JAVA_HOME 'bin\javac.exe'
    } elseif (Get-Command javac -ErrorAction SilentlyContinue) {
        $javacPath = (Get-Command javac).Source
    }
    $keytoolPath = $null
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\keytool.exe'))) {
        $keytoolPath = Join-Path $env:JAVA_HOME 'bin\keytool.exe'
    } elseif (Get-Command keytool -ErrorAction SilentlyContinue) {
        $keytoolPath = (Get-Command keytool).Source
    }
    if (-not $javacPath) {
        Write-Fail "javac not found - this looks like a JRE, not a JDK" `
            "Gradle compiles .java sources, which requires javac.exe. Install a *JDK* distribution (Temurin/OpenJDK/Corretto/Zulu). On Windows the JRE and JDK installers have nearly identical names; pick the one that ships javac.exe."
    } else {
        Write-Info "javac found at $javacPath"
    }
    if (-not $keytoolPath) {
        Write-Warn "keytool not found - corporate-CA import will not work" `
            "Install a full JDK or point JAVA_HOME at one that includes bin\keytool.exe."
    }
}

# Detect a stale org.gradle.java.home override. The property hard-pins
# the Gradle daemon's JDK and overrides toolchains. Common enterprise
# failure: the user configured it for a Gradle 8 build years ago,
# pointing at JDK 11; the Gradle 9.x daemon refuses to start with
# "Value 'C:\...\jdk-11' given for org.gradle.java.home Gradle property
# is invalid (Java version too old)."
if (Test-Path $gradleProps) {
    $gpForPin = Get-Content $gradleProps -Raw
    if ($gpForPin -match '(?m)^\s*org\.gradle\.java\.home\s*=\s*(.+)$') {
        $pinnedJavaHome = $Matches[1].Trim()
        Write-Info "org.gradle.java.home = $pinnedJavaHome  (pinned in $gradleProps)"
        if (-not (Test-Path (Join-Path $pinnedJavaHome 'bin\java.exe'))) {
            Write-Fail "org.gradle.java.home points at a path with no bin\java.exe: $pinnedJavaHome" `
                "Either delete the line from $gradleProps so Gradle uses JAVA_HOME, or update it to a valid JDK 17-25 install root."
        } else {
            $pinnedVer = & (Join-Path $pinnedJavaHome 'bin\java.exe') -version 2>&1 | Select-Object -First 1
            if ($pinnedVer -match '"(\d+)') {
                $pm = [int]$Matches[1]
                if ($pm -ge 17 -and $pm -le 25) {
                    Write-Pass "org.gradle.java.home JDK $pm is in range ($pinnedVer)"
                } else {
                    Write-Fail "org.gradle.java.home pins Gradle to JDK $pm; repo needs 17-25" `
                        "Gradle 9.4.1 will refuse to start. Delete the org.gradle.java.home line OR update it to a 17-25 install."
                }
            }
        }
    }
}

# ---------------------------------------------------------------------------
# PowerShell execution policy - a silent first-time blocker on Windows.
# Fresh enterprise-managed installs default to Restricted or AllSigned;
# running this script (or ./gradlew.bat after it) fails with
#   ".\scripts\build-health-check.ps1 cannot be loaded because running
#   scripts is disabled on this system." The fix is to scope the relax
#   to the CurrentUser. Warn unconditionally but tell the user the fix.
# ---------------------------------------------------------------------------
Write-Section "PowerShell execution policy"
try {
    $currentPolicy = Get-ExecutionPolicy -Scope CurrentUser
    $effectivePolicy = Get-ExecutionPolicy
    Write-Info "Effective policy: $effectivePolicy  (CurrentUser scope: $currentPolicy)"
    switch ($effectivePolicy) {
        'Restricted' {
            Write-Fail "Effective policy = Restricted. PowerShell scripts cannot run at all." `
                "Run once in an elevated prompt: Set-ExecutionPolicy -Scope CurrentUser RemoteSigned"
        }
        'AllSigned' {
            Write-Warn "Effective policy = AllSigned. Unsigned scripts (including this one and gradlew.bat's helpers) will be blocked." `
                "Run once: Set-ExecutionPolicy -Scope CurrentUser RemoteSigned"
        }
        'Bypass' {
            Write-Pass "Effective policy = Bypass (scripts run without restriction)."
        }
        'RemoteSigned' {
            Write-Pass "Effective policy = RemoteSigned (local scripts run; downloaded scripts need a signature)."
        }
        'Unrestricted' {
            Write-Pass "Effective policy = Unrestricted."
        }
        default {
            Write-Info "Effective policy = $effectivePolicy (review if scripts are being blocked)."
        }
    }
} catch {
    Write-Info "Could not read ExecutionPolicy: $($_.Exception.Message)"
}

# ---------------------------------------------------------------------------
# Proxy detection
# ---------------------------------------------------------------------------
Write-Section "Proxy configuration"

# Gradle reads TWO gradle.properties:
#   (1) USER-LEVEL at %USERPROFILE%\.gradle\gradle.properties -- proxy
#       host/port/creds + trust store go here. Per-user, per-machine,
#       NEVER committed to git.
#   (2) PROJECT-LEVEL at <project>\gradle.properties -- build tuning
#       (JVM args, parallel, caching). Committed to git. Proxy settings
#       DO NOT belong here.
# This script only reads/writes (1). (2) is never modified.
Write-Info "User-level gradle.properties (proxy + creds live here):"
Write-Info "  $gradleProps"
# Check every sub-project wrapper for accidentally-committed proxy or
# credential lines. Historically only banking-app was scanned.
foreach ($proj in @('banking-app', 'bench-harness', 'bench-cli', 'bench-webui')) {
    $projProps = Join-Path $script:RepoRoot "$proj\gradle.properties"
    if (-not (Test-Path $projProps)) { continue }
    Write-Info "Project-level gradle.properties (NOT used for proxy; only build tuning):"
    Write-Info "  $projProps"
    $content = Get-Content $projProps -Raw
    if ($content -match 'systemProp\.(http|https)\.proxy|orgInternalMaven(User|Password)|artifactoryResolver(Username|Password)') {
        Write-Warn "Project-level gradle.properties contains proxy/credential lines!" `
            "These would be committed to git. Move them to $gradleProps and remove from $projProps."
    }
}

$proxyEnv = @{
    HTTPS_PROXY = $env:HTTPS_PROXY
    HTTP_PROXY  = $env:HTTP_PROXY
    NO_PROXY    = $env:NO_PROXY
}
$envProxySet = $false
foreach ($k in $proxyEnv.Keys) {
    if ($proxyEnv[$k]) {
        Write-Info "$k = $($proxyEnv[$k])"
        $envProxySet = $true
    }
}

# Flag JVM-level env vars that silently inject args into every JVM
# launched (including the Gradle wrapper). Classic gotcha: a forgotten
# -Dhttp.proxyHost here overrides what's in gradle.properties.
foreach ($vname in @('GRADLE_OPTS', 'JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JAVA_OPTS')) {
    $val = [Environment]::GetEnvironmentVariable($vname)
    if ($val) {
        Write-Info "$vname = $val"
        if ($val -match '(?i)proxy|truststore|javax\.net\.ssl') {
            Write-Warn "$vname contains TLS/proxy JVM args that may conflict with gradle.properties." `
                "JVM-level env vars apply to every java.exe process; if they disagree with $gradleProps settings, the env vars win."
        }
    }
}

# Windows WinHTTP / WinINET proxy detection. VSCode and Edge use
# WinINET (per-user IE proxy). Gradle's JVM ignores both — reading
# only -Dhttp.proxyHost or gradle.properties. Very common mismatch:
# "VSCode works, Gradle doesn't" because gradle.properties has no
# proxy but Windows is actually using a WinHTTP proxy configured via
# GPO. Surface both pictures so the operator can compare.
try {
    $winhttp = & netsh winhttp show proxy 2>&1 | Out-String
    if ($winhttp -match 'Proxy Server\(s\)\s*:\s*([^\r\n]+)') {
        $wh = $Matches[1].Trim()
        if ($wh -ne '(none)' -and $wh -ne 'Direct access (no proxy server).' -and $wh) {
            Write-Info "WinHTTP proxy (system scope, JVM does NOT read this): $wh"
        } else {
            Write-Info "WinHTTP proxy: Direct access (no system proxy)."
        }
    }
} catch { }

try {
    $ieProxy = Get-ItemProperty -Path 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings' -ErrorAction SilentlyContinue
    if ($ieProxy -and $ieProxy.ProxyEnable -eq 1 -and $ieProxy.ProxyServer) {
        Write-Info "WinINET proxy (per-user IE/Edge, JVM does NOT read this): $($ieProxy.ProxyServer)"
        if ($ieProxy.AutoConfigURL) {
            Write-Warn "WinINET uses an auto-config URL (PAC): $($ieProxy.AutoConfigURL)" `
                "The JVM has NO built-in PAC parser. Decide a single static proxy from the PAC, paste it into $gradleProps."
        }
    }
} catch { }

# $gradleProps was defined at the top of the script so every earlier
# section could safely reference it. Just declare the derived state here.
$gradleProxyKeys = @('systemProp.https.proxyHost', 'systemProp.http.proxyHost')
$gradleProxyConfigured = $false
$gradleProxyHost = $null
$gradleProxyPort = $null
if (Test-Path $gradleProps) {
    $content = Get-Content $gradleProps -Raw
    foreach ($key in $gradleProxyKeys) {
        if ($content -match "(?m)^\s*$([regex]::Escape($key))\s*=\s*(\S+)") {
            Write-Pass "$gradleProps sets $key = $($Matches[1])"
            $gradleProxyConfigured = $true
            if ($key -eq 'systemProp.https.proxyHost') { $gradleProxyHost = $Matches[1] }
        }
    }
    if ($content -match '(?m)^\s*systemProp\.https\.proxyPort\s*=\s*(\S+)') {
        $gradleProxyPort = $Matches[1]
    }
    if (-not $gradleProxyConfigured) {
        Write-Info "$gradleProps exists but does not configure proxies."
    }
} else {
    Write-Info "$gradleProps does not exist (no user-level Gradle config yet)."
}

# --- VSCode proxy detection -----------------------------------------
# VSCode stores its HTTP proxy in settings.json (JSONC). If the user has
# a working proxy there, Gradle probably needs the same; offer to sync.
$vscodeSettings = Join-Path $env:APPDATA 'Code\User\settings.json'
if (-not (Test-Path $vscodeSettings)) {
    $vscodeSettings = Join-Path $env:APPDATA 'Code - Insiders\User\settings.json'
}
$vscodeProxy = $null
$vscodeNoProxy = @()
$vscodeStrictSsl = $null
$vscodeProxySupport = $null
if (Test-Path $vscodeSettings) {
    # Strip // line comments before parsing (settings.json is JSONC).
    $raw = Get-Content $vscodeSettings -Raw
    $stripped = [regex]::Replace($raw, '(?m)^\s*//.*$', '')
    try {
        $json = ConvertFrom-Json $stripped -ErrorAction Stop
        $vscodeProxy = $json.'http.proxy'
        if ($json.PSObject.Properties.Name -contains 'http.noProxy') {
            $vscodeNoProxy = @($json.'http.noProxy')
        }
        if ($json.PSObject.Properties.Name -contains 'http.proxyStrictSSL') {
            $vscodeStrictSsl = $json.'http.proxyStrictSSL'
        }
        if ($json.PSObject.Properties.Name -contains 'http.proxySupport') {
            $vscodeProxySupport = $json.'http.proxySupport'
        }
    } catch {
        Write-Warn "Could not parse $vscodeSettings as JSON: $($_.Exception.Message)"
    }
    if ($vscodeProxy) {
        Write-Info "VSCode http.proxy = $vscodeProxy  (from $vscodeSettings)"

        # Compare to Gradle's current proxy host.
        if ($vscodeProxy -match '^https?://([^:/]+)(?::(\d+))?') {
            $vsHost = $Matches[1]
            $vsPort = if ($Matches[2]) { $Matches[2] } else { '80' }

            # Convert VSCode's `http.noProxy` array to Gradle's
            # pipe-delimited `nonProxyHosts` format. If the user has no
            # exclusions configured, fall back to a sensible localhost
            # default so local services (bench-webui, H2, etc) aren't
            # accidentally routed through the proxy.
            $nonProxyList = if ($vscodeNoProxy -and $vscodeNoProxy.Count -gt 0) {
                $vscodeNoProxy
            } else {
                @('localhost', '127.0.0.1', '*.local')
            }
            $nonProxyJoined = ($nonProxyList -join '|')

            $needsSync = $false
            if (-not $gradleProxyConfigured) {
                $needsSync = $true
                Write-Warn "Gradle has no proxy configured but VSCode uses $vsHost`:$vsPort." `
                    "Syncing it into $gradleProps will let 'gradlew build' use the same proxy."
            } elseif ($gradleProxyHost -and ($gradleProxyHost -ne $vsHost -or $gradleProxyPort -ne $vsPort)) {
                $needsSync = $true
                Write-Warn "Gradle proxy ($gradleProxyHost`:$gradleProxyPort) differs from VSCode ($vsHost`:$vsPort)." `
                    "If VSCode reaches the network and Gradle doesn't, sync Gradle to VSCode's value."
            } else {
                Write-Pass "Gradle and VSCode proxies match."
            }

            if ($needsSync -and -not $NonInteractive) {
                # Show the user everything we're about to write, so they
                # can confirm the full change -- not just host/port.
                Write-Host ""
                Write-Host "  The following changes will be written to $gradleProps :" -ForegroundColor Yellow
                Write-Host "    systemProp.https.proxyHost       = $vsHost"
                Write-Host "    systemProp.https.proxyPort       = $vsPort"
                Write-Host "    systemProp.https.nonProxyHosts   = $nonProxyJoined"
                Write-Host "    systemProp.http.proxyHost        = $vsHost"
                Write-Host "    systemProp.http.proxyPort        = $vsPort"
                Write-Host "    systemProp.http.nonProxyHosts    = $nonProxyJoined"
                if ($vscodeNoProxy -and $vscodeNoProxy.Count -gt 0) {
                    Write-Host "    (exclusions sourced from VSCode http.noProxy: $($vscodeNoProxy -join ', '))" -ForegroundColor DarkGray
                } else {
                    Write-Host "    (VSCode http.noProxy is empty -- applied default localhost/loopback exclusions)" -ForegroundColor DarkGray
                }
                if ($null -ne $vscodeStrictSsl -and -not $vscodeStrictSsl) {
                    Write-Host "    NOTE: VSCode has http.proxyStrictSSL = false." -ForegroundColor DarkYellow
                    Write-Host "          Gradle does NOT inherit this -- Gradle still validates TLS." -ForegroundColor DarkYellow
                    Write-Host "          If your proxy intercepts HTTPS, follow the TLS chain prompt further down." -ForegroundColor DarkYellow
                }
                if ($vscodeProxySupport) {
                    Write-Host "    (VSCode http.proxySupport = '$vscodeProxySupport' -- informational only)" -ForegroundColor DarkGray
                }
                Write-Host "  Existing $gradleProps (if any) will be backed up alongside it before the edit." -ForegroundColor Yellow
                $resp = Read-Host "  Proceed with the write? [y/N]"
                if ($resp -match '^(y|yes)$') {
                    $null = New-Item -ItemType Directory -Force -Path (Split-Path $gradleProps)
                    if (Test-Path $gradleProps) {
                        $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
                        $backup = "$gradleProps.backup-$stamp"
                        Copy-Item -Path $gradleProps -Destination $backup -Force
                        Write-Pass "Backed up existing config to $backup"
                    }
                    $existing = if (Test-Path $gradleProps) { Get-Content $gradleProps -Raw } else { '' }
                    # Drop any prior systemProp.* proxy lines so we don't double them up.
                    $cleaned = ($existing -replace '(?m)^\s*systemProp\.(https?\.proxy(Host|Port)|https?\.nonProxyHosts)\s*=.*\r?\n?', '')
                    $append = @"

# Added by build-health-check.ps1 on $(Get-Date -Format 's') -- synced from VSCode http.proxy
systemProp.https.proxyHost=$vsHost
systemProp.https.proxyPort=$vsPort
systemProp.https.nonProxyHosts=$nonProxyJoined
systemProp.http.proxyHost=$vsHost
systemProp.http.proxyPort=$vsPort
systemProp.http.nonProxyHosts=$nonProxyJoined
"@
                    Set-Content -Path $gradleProps -Value ($cleaned + $append) -Encoding utf8
                    Write-Pass "Updated $gradleProps -- re-run this script to re-verify."
                    Write-Info "To revert: move the .backup-* file back over $gradleProps."
                } else {
                    Write-Info "Skipped. You can copy the proxy manually into $gradleProps."
                }
            }
        }
    } else {
        Write-Info "VSCode settings.json present but no http.proxy key."
    }
} else {
    if ($Detailed) { Write-Info "VSCode settings.json not found (skipped VSCode proxy sync)." }
}

# Detect "behind a proxy" mode by trying to reach the public Internet directly.
$directReachable = $false
try {
    $r = Invoke-WebRequest -Uri 'https://www.gstatic.com/generate_204' `
        -Method Head -TimeoutSec 5 -UseBasicParsing -NoProxy -ErrorAction Stop
    if ($r.StatusCode -in 200, 204) { $directReachable = $true }
} catch { }

if ($directReachable) {
    Write-Pass "Direct Internet reachable (no proxy detected on this network)."
    if ($envProxySet -or $gradleProxyConfigured) {
        Write-Warn "Proxy is configured but direct Internet works; harmless, but check that the proxy is actually required on this network."
    }
} else {
    Write-Info "Direct Internet not reachable -> assuming corporate proxy required."
    if (-not $envProxySet -and -not $gradleProxyConfigured) {
        Write-Fail "No proxy configured anywhere (env vars unset, gradle.properties has no systemProp.https.proxyHost)" `
            "Add proxy entries to $gradleProps. See README.md 'Behind a corporate proxy'."
    }
}

# ---------------------------------------------------------------------------
# Corporate repository configuration scan
#
# The project builds with Gradle only; we never invoke mvn. But many
# enterprises hand out Artifactory URL + credentials via Maven's
# settings.xml format (for example from JFrog's "Set Me Up -> Maven"
# workflow). This section treats settings.xml and maven-wrapper.properties
# as READ-ONLY data sources: we parse them, extract the URL + any
# attached <server> creds, then translate those values into Gradle-native
# config later in the Maven-Central remediation block. We never write
# to ~/.m2/ -- that's for Maven to consume, and we're not using Maven.
#
# Interactive: asks first whether the operator has a corporate
# settings.xml or maven-wrapper.properties, and offers three search
# modes. In --NonInteractive mode (or when -MavenConfigPath was
# supplied), skip the prompt and just scan.
# ---------------------------------------------------------------------------
# ---------------------------------------------------------------------------
# Gradle properties consistency
# Verifies ~/.gradle/gradle.properties is internally coherent and aligned
# with the proxy we already validated:
#   - http vs https proxy host/port parity
#   - matches VSCode http.proxy (when we parsed one earlier)
#   - on Windows, if trustStore is set, trustStoreType == Windows-ROOT
#   - credential pairs we write (orgInternalMaven*, systemProp.gradle.wrapper*,
#     artifactoryResolver*) all share the same username/password so every
#     Gradle call path (init script, wrapper download, direct Artifactory)
#     authenticates the same way
# ---------------------------------------------------------------------------
Write-Section "Gradle properties consistency"

if (-not (Test-Path $gradleProps)) {
    Write-Info "$gradleProps does not exist -- skipping consistency check."
} else {
    $gpRaw = Get-Content $gradleProps -Raw
    function Read-Prop([string]$name) {
        if ($gpRaw -match "(?m)^\s*$([regex]::Escape($name))\s*=\s*(\S+)") { return $Matches[1] }
        return $null
    }
    $httpsHost = Read-Prop 'systemProp.https.proxyHost'
    $httpsPort = Read-Prop 'systemProp.https.proxyPort'
    $httpHost  = Read-Prop 'systemProp.http.proxyHost'
    $httpPort  = Read-Prop 'systemProp.http.proxyPort'
    $httpsNon  = Read-Prop 'systemProp.https.nonProxyHosts'
    $httpNon   = Read-Prop 'systemProp.http.nonProxyHosts'
    $tsStore   = Read-Prop 'systemProp.javax.net.ssl.trustStore'
    $tsType    = Read-Prop 'systemProp.javax.net.ssl.trustStoreType'

    if ($httpsHost -or $httpHost) {
        if ($httpsHost -eq $httpHost -and $httpsPort -eq $httpPort) {
            Write-Pass "HTTP and HTTPS proxy match: ${httpsHost}:${httpsPort}"
        } else {
            Write-Warn "HTTP and HTTPS proxy settings DIFFER:" `
                "https=${httpsHost}:${httpsPort}  http=${httpHost}:${httpPort}. Usually both should point at the same corp proxy."
        }
        if ($httpsNon -ne $httpNon) {
            Write-Warn "HTTP and HTTPS nonProxyHosts DIFFER:" "https=$httpsNon   http=$httpNon"
        }
    } else {
        Write-Info "No proxy configured in $gradleProps."
    }

    if ($vscodeProxy -and $vscodeProxy -match '^https?://([^:/]+)(?::(\d+))?') {
        $vsH = $Matches[1]
        $vsP = if ($Matches[2]) { $Matches[2] } else { '80' }
        if ($httpsHost -eq $vsH -and $httpsPort -eq $vsP) {
            Write-Pass "gradle.properties proxy matches VSCode http.proxy (${vsH}:${vsP})."
        } elseif ($httpsHost) {
            Write-Warn "gradle.properties proxy (${httpsHost}:${httpsPort}) does NOT match VSCode http.proxy (${vsH}:${vsP})." `
                "VSCode reaches the network; Gradle's setting differs. Re-run the Proxy prompt above to sync."
        }
    }

    # Windows-specific trust-store check.
    $isWindows = ([System.Environment]::OSVersion.Platform -eq 'Win32NT') -or ($env:OS -eq 'Windows_NT')
    if ($isWindows) {
        if ($tsStore) {
            if ($tsType -eq 'Windows-ROOT') {
                Write-Pass "trustStore='$tsStore', trustStoreType=Windows-ROOT (Gradle uses the Windows cert store -- correct)."
            } elseif ($tsType) {
                Write-Warn "trustStoreType=$tsType (expected Windows-ROOT on Windows)." `
                    "Your corporate root CA is almost certainly installed in the Windows certificate store via GPO/MDM; setting trustStoreType=Windows-ROOT is how Gradle's JVM reads it."
            } else {
                Write-Warn "trustStore is set to '$tsStore' but trustStoreType is not declared." `
                    "On Windows, add  systemProp.javax.net.ssl.trustStoreType=Windows-ROOT  so Gradle uses the Windows cert store."
            }
        } else {
            Write-Info "No systemProp.javax.net.ssl.trustStore override in $gradleProps -- Gradle uses the JDK's bundled cacerts."
        }
    } elseif ($tsStore) {
        Write-Info "trustStore override: $tsStore (type=$(if ($tsType) { $tsType } else { 'default' }))"
    }

    # Credential-pair consistency across the three names our init script,
    # wrapper download, and Artifactory resolver expect.
    $credPairs = @(
        @{ User='orgInternalMavenUser';          Pass='orgInternalMavenPassword';       Purpose='init-script mirror authentication' },
        @{ User='systemProp.gradle.wrapperUser'; Pass='systemProp.gradle.wrapperPassword'; Purpose='Gradle wrapper distribution download' },
        @{ User='artifactoryResolverUsername';   Pass='artifactoryResolverPassword';    Purpose='direct Artifactory resolver access' }
    )
    $seenUser = $null; $seenPass = $null; $inconsistent = $false; $missing = @()
    foreach ($p in $credPairs) {
        $u = Read-Prop $p.User
        $pw = Read-Prop $p.Pass
        if (-not $u -or -not $pw) {
            $missing += "$($p.User) / $($p.Pass)  ($($p.Purpose))"
            continue
        }
        if ($null -eq $seenUser) { $seenUser = $u; $seenPass = $pw; continue }
        if ($u -ne $seenUser -or $pw -ne $seenPass) { $inconsistent = $true }
    }
    if ($missing.Count -eq 0 -and -not $inconsistent -and $seenUser) {
        Write-Pass "All three credential pairs present and consistent (user '$seenUser')."
    } elseif ($missing.Count -gt 0) {
        Write-Warn "Missing Gradle credential pair(s) in $gradleProps; run the Maven-Central remediation below to inject them." `
            ("Missing: " + ($missing -join '; '))
    }
    if ($inconsistent) {
        Write-Warn "Credential pairs in $gradleProps DISAGREE across names." `
            "Every pair should carry the same username/password from settings.xml. Re-run the remediation to overwrite."
    }
}

Write-Section "Corporate repository configuration"

$mavenHints = New-Object System.Collections.ArrayList
# Keyed by "$source||$serverId" -> @{ Username=...; Password=... }. Lets us
# look up creds for any hint that carries Source + RepoId.
$serverCreds = @{}

# Resolve the real Downloads folder. On Windows, OneDrive frequently
# redirects it off $env:USERPROFILE\Downloads; the Shell known-folder
# registry entry holds the authoritative path.
$downloadsDir = $null
try {
    $dkey = Get-ItemProperty -Path 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Explorer\User Shell Folders' `
        -Name '{374DE290-123F-4565-9164-39C4925E467B}' -ErrorAction Stop
    $downloadsDir = [Environment]::ExpandEnvironmentVariables($dkey.'{374DE290-123F-4565-9164-39C4925E467B}')
} catch {
    $downloadsDir = Join-Path $env:USERPROFILE 'Downloads'
}

# Default lookup lists -- used in non-interactive / "[d] scan defaults" path.
$defaultSettings = @(
    (Join-Path $env:USERPROFILE '.m2\settings.xml'),
    (Join-Path $downloadsDir 'settings.xml'),
    (Join-Path $env:USERPROFILE 'Downloads\settings.xml'),
    (Join-Path $env:USERPROFILE 'OneDrive\Downloads\settings.xml'),
    '.\settings.xml'
) | Select-Object -Unique
$defaultWrappers = @(
    (Join-Path $env:USERPROFILE '.m2\wrapper\maven-wrapper.properties'),
    (Join-Path $env:USERPROFILE '.mvn\wrapper\maven-wrapper.properties'),
    (Join-Path $downloadsDir 'maven-wrapper.properties'),
    (Join-Path $env:USERPROFILE 'Downloads\maven-wrapper.properties'),
    (Join-Path $env:USERPROFILE 'OneDrive\Downloads\maven-wrapper.properties'),
    '.\maven-wrapper.properties',
    '.\.mvn\wrapper\maven-wrapper.properties'
) | Select-Object -Unique

# Inner helpers -------------------------------------------------------------
function Parse-SettingsXml([string]$path) {
    if (-not (Test-Path $path)) { return }
    try {
        $xml = Get-Content $path -Raw
        $extracted = 0

        # Pattern 1: <server><id>X</id><username>U</username><password>P</password></server>
        # -- parsed FIRST so repository blocks below can attach creds by
        # id. We flag three situations where the value won't work as-is:
        #   * Maven-encrypted password  {...}  (needs settings-security.xml)
        #   * Maven template variable   ${env.X} / ${security.foo}
        #     (Maven resolves client-side; curl doesn't -- the literal
        #     placeholder will get sent and Artifactory will 401)
        #   * Leading/trailing whitespace (rare but trips parsers)
        foreach ($m in [regex]::Matches($xml, '(?s)<server>(.*?)</server>')) {
            $body = $m.Groups[1].Value
            $sid = if ($body -match '<id>\s*([^<]+)\s*</id>')       { $Matches[1].Trim() } else { '' }
            $usr = if ($body -match '<username>\s*([^<]+)\s*</username>') { $Matches[1].Trim() } else { '' }
            $pwd = if ($body -match '<password>\s*([^<]+)\s*</password>') { $Matches[1].Trim() } else { '' }
            if ($sid) {
                $encrypted  = ($pwd -match '^\s*\{.*\}\s*$')
                $isTemplate = ($usr -match '\$\{[^}]+\}') -or ($pwd -match '\$\{[^}]+\}')
                $templateResolved = $null
                if ($isTemplate) {
                    # Attempt to resolve ${env.X} references from the real
                    # process environment so a token kept out of VCS
                    # still reaches our probe. Other templates we can't
                    # resolve without Maven.
                    $resolved = $pwd -replace '\$\{env\.([^}]+)\}', {
                        param($m)
                        $v = [Environment]::GetEnvironmentVariable($m.Groups[1].Value)
                        if ($v) { $v } else { $m.Value }
                    }
                    if ($resolved -ne $pwd -and $resolved -notmatch '\$\{[^}]+\}') {
                        $pwd = $resolved
                        $templateResolved = 'env'
                    }
                }
                $script:serverCreds["$path||$sid"] = @{
                    Username  = $usr
                    Password  = $pwd
                    Encrypted = $encrypted
                    Template  = $isTemplate -and ($null -eq $templateResolved)
                }
                if (-not $usr) {
                    Write-Info "Parsed $path -- <server id='$sid'> declared (no username; likely token-only)"
                } elseif ($encrypted) {
                    Write-Warn "Parsed $path -- <server id='$sid'> username '$usr' but password is Maven-ENCRYPTED {...}" `
                        "Decrypt with 'mvn --encrypt-password' (requires ~/.m2/settings-security.xml), paste the plaintext back into settings.xml, or export it into an env var referenced by \${env.NAME}."
                } elseif ($isTemplate -and ($null -eq $templateResolved)) {
                    Write-Warn "Parsed $path -- <server id='$sid'> username/password contains a Maven template variable (\${...})" `
                        "Maven resolves these client-side; curl doesn't. Replace \${env.X} / \${security.X} in settings.xml with literal values, or set the matching env var before running this script."
                } elseif ($templateResolved -eq 'env') {
                    Write-Info "Parsed $path -- <server id='$sid'> username '$usr', password resolved from env var at runtime"
                } else {
                    Write-Info "Parsed $path -- <server id='$sid'> credential block, username '$usr'"
                }
            }
        }

        # Pattern 2: <mirror><url>X</url></mirror> -- classic "send every
        # repo through this URL" approach. Mirror <id> may map to a
        # <server> block for creds.
        foreach ($m in [regex]::Matches($xml, '(?s)<mirror>(.*?)</mirror>')) {
            $mbody = $m.Groups[1].Value
            if ($mbody -match '<url>\s*([^<]+)\s*</url>') {
                $u = $Matches[1].Trim()
                $mid = if ($mbody -match '<id>\s*([^<]+)\s*</id>') { $Matches[1].Trim() } else { '' }
                [void]$script:mavenHints.Add(@{
                    Source = $path
                    Url    = $u
                    Note   = 'from <mirror> block in settings.xml'
                    RepoId = $mid
                })
                Write-Pass "Parsed $path -- extracted mirror URL: $u"
                $extracted++
            }
        }

        # Pattern 3: <profile><repositories><repository><url>X</url>...
        # This is what JFrog Artifactory's "Set Me Up -> Maven" workflow
        # generates. The <id> on each <repository> determines which
        # default it overrides: id=central replaces Maven Central, other
        # ids add extra repos. Capture both <repository> and
        # <pluginRepository> URLs, and flag id=central as the primary
        # candidate for Gradle's mavenCentral() redirect.
        $repoRegex = '(?s)<(repository|pluginRepository)>(.*?)</(\1)>'
        foreach ($m in [regex]::Matches($xml, $repoRegex)) {
            $kind = $m.Groups[1].Value      # 'repository' or 'pluginRepository'
            $body = $m.Groups[2].Value
            if ($body -match '<url>\s*([^<]+)\s*</url>') {
                $u = $Matches[1].Trim()
                $rid = if ($body -match '<id>\s*([^<]+)\s*</id>') { $Matches[1].Trim() } else { '' }
                $name = if ($body -match '<name>\s*([^<]+)\s*</name>') { $Matches[1].Trim() } else { '' }
                $centralOverride = ($rid -eq 'central')
                $noteTail = if ($centralOverride) {
                    "from <$kind id=""central""> (OVERRIDES mavenCentral() via Maven's default-id resolution)"
                } elseif ($rid) {
                    "from <$kind id=""$rid""> (adds as alternate repo, does NOT replace mavenCentral())"
                } else {
                    "from <$kind> (no id) in settings.xml"
                }
                [void]$script:mavenHints.Add(@{
                    Source = $path
                    Url    = $u
                    Note   = $noteTail
                    RepoId = $rid
                })
                $label = if ($name) { "$u ($name)" } else { $u }
                $credNote = if ($rid -and $script:serverCreds.ContainsKey("$path||$rid")) {
                    "  (creds attached from matching <server id='$rid'>)"
                } else { '' }
                if ($centralOverride) {
                    Write-Pass "Parsed $path -- Central-override $kind url: $label$credNote"
                } else {
                    Write-Info "Parsed $path -- additional <$kind id='$rid'> url: $label$credNote"
                }
                $extracted++
            }
        }

        if ($extracted -eq 0) {
            Write-Warn "$path has no <mirror>, <repository>, or <pluginRepository> URL -- nothing to extract."
        }
    } catch { Write-Fail "Could not parse $path as XML: $($_.Exception.Message)" }
}

function Parse-MavenWrapper([string]$path) {
    if (-not (Test-Path $path)) { return }
    $props = Get-Content $path -Raw
    if ($props -match '(?m)^\s*distributionUrl\s*=\s*(\S+)') {
        $distUrl = $Matches[1] -replace '\\:', ':'
        $base = $null
        if ($distUrl -match '^(https?://[^/]+/artifactory/[^/]+/)') { $base = $Matches[1] }
        elseif ($distUrl -match '^(https?://[^/]+/repository/[^/]+/)') { $base = $Matches[1] }
        elseif ($distUrl -match '^(https?://[^/]+/)')                    { $base = $Matches[1] }
        if ($base) {
            [void]$script:mavenHints.Add(@{
                Source = $path
                Url    = $base
                Note   = 'derived from distributionUrl in maven-wrapper.properties'
            })
            Write-Pass "Parsed $path -- derived base URL: $base"
        } else {
            Write-Warn "$path has distributionUrl '$distUrl' but could not extract a base URL."
        }
    } else {
        Write-Warn "$path has no distributionUrl line -- nothing to extract."
    }
}

# Probe Gradle's REAL build config -- uses mavenCentral() and
# gradlePluginPortal() exactly as the project does, with the user's
# proxy + init scripts applied naturally. Pattern A users (proxy
# alone, no mirror) get a clean PASS without any candidate URL. Pattern
# B users (mirror via init script) also pass because their init script
# rewrites mavenCentral() before resolution.
function Probe-RealBuildConfig {
    $result = @{
        Status='fail'; Code=0; Message=''; DiagLines=@(); TmpDir=$null
        AuthMode='gradle-real'; AuthPreview=''
    }
    $wrapper = $null
    $roots = @(
        (Get-Location).Path,
        (Split-Path (Get-Location).Path),
        (Join-Path (Get-Location).Path 'banking-app'),
        (Join-Path (Get-Location).Path 'bench-webui'),
        (Join-Path (Get-Location).Path 'bench-cli'),
        (Join-Path (Get-Location).Path 'bench-harness')
    )
    foreach ($root in $roots) {
        $candidate = Join-Path $root 'gradlew.bat'
        if (Test-Path $candidate) { $wrapper = $candidate; break }
    }
    if (-not $wrapper) {
        $result.Message = "no gradlew.bat wrapper found in nearby project dirs"
        return $result
    }
    $tmpdir = Join-Path $env:TEMP ("gradle-real-probe-" + [Guid]::NewGuid().ToString().Substring(0,8))
    [void](New-Item -ItemType Directory -Path $tmpdir -Force)
    $build = @"
plugins { java }
repositories {
    // Real-build config: same defaults as a Spring Boot / Kotlin project.
    // The user's ~/.gradle/init.d/ scripts and gradle.properties proxy
    // apply naturally -- nothing here forces a specific URL.
    mavenCentral()
    gradlePluginPortal()
}
configurations { create("mirrorProbe") }
dependencies {
    // Three artifacts representative of what the actual build pulls.
    add("mirrorProbe", "org.slf4j:slf4j-api:2.0.16")
    add("mirrorProbe", "com.google.code.gson:gson:2.10.1")
    add("mirrorProbe", "org.springframework.boot:spring-boot:3.3.4")
}
tasks.register("checkMirror") {
    notCompatibleWithConfigurationCache("probe resolves eagerly")
    doLast {
        val files = configurations["mirrorProbe"].resolve()
        println("PROBE_OK: resolved " + files.size + " file(s)")
    }
}
"@
    Set-Content -Path (Join-Path $tmpdir 'settings.gradle.kts') -Value 'rootProject.name = "real-probe"'
    Set-Content -Path (Join-Path $tmpdir 'build.gradle.kts') -Value $build

    $gradleUserHome = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $env:USERPROFILE '.gradle' }
    Write-Info "    Real-build probe command:"
    Write-Info "      `"$wrapper`" -p `"$tmpdir`" --refresh-dependencies --console=plain --no-configuration-cache --no-daemon --stacktrace checkMirror"
    Write-Info "    Reads: $gradleUserHome\gradle.properties (proxy + creds)"
    Write-Info "    Init scripts applied (any rewrite happens here):"
    if (Test-Path (Join-Path $gradleUserHome 'init.d')) {
        Get-ChildItem (Join-Path $gradleUserHome 'init.d') -File -ErrorAction SilentlyContinue | ForEach-Object {
            Write-Info "      $($_.FullName)"
        }
    } else {
        Write-Info "      (none -- init.d directory does not exist; will hit mavenCentral() directly)"
    }
    Write-Info "    Probe project preserved at (until success): $tmpdir"
    Write-Info "    Running probe..."

    $output = & $wrapper -p $tmpdir `
        --refresh-dependencies --console=plain `
        --no-configuration-cache --no-daemon --stacktrace `
        checkMirror 2>&1
    $exitCode = $LASTEXITCODE
    $outputText = ($output | Out-String)

    if ($exitCode -eq 0 -and $outputText -match 'PROBE_OK: resolved \d+') {
        Remove-Item $tmpdir -Recurse -Force -ErrorAction SilentlyContinue
        $result.Status = 'reachable'; $result.Code = 200
        $result.Message = "Real-build config resolves slf4j+gson+spring-boot (exit 0)"
        return $result
    }

    # Categorize + collect diagnostic lines, same logic as the per-URL probe.
    $diag = New-Object System.Collections.ArrayList
    $inWentWrong = $false
    foreach ($line in $output) {
        $s = $line.ToString()
        if ($s -match '^\s*\*\s*What went wrong:') { $inWentWrong = $true; continue }
        if ($inWentWrong -and $s -match '^\s*\*\s*Try:') { $inWentWrong = $false }
        if ($inWentWrong -and $s.Trim()) { [void]$diag.Add($s.Trim()) }
    }
    foreach ($line in $output) {
        $s = $line.ToString()
        if ($s -match 'Could not resolve|Received fatal alert|Failed to transfer|Could not [Gg]et|Could not HEAD') {
            if ($diag -notcontains $s.Trim()) { [void]$diag.Add($s.Trim()) }
        }
    }
    $result.DiagLines = @($diag | Select-Object -First 6)
    $result.TmpDir = $tmpdir

    if ($outputText -match '401|[Uu]nauthorized') {
        $result.Status = 'gradle-fail'; $result.FailKind = 'auth'
        $result.Message = "Real-build probe got HTTP 401 -- proxy or mirror credentials wrong"
    } elseif ($outputText -match 'PKIX|SSLHandshake|trust|certificate') {
        $result.Status = 'gradle-fail'; $result.FailKind = 'tls'
        $result.Message = "Real-build probe TLS handshake failed (corporate CA likely missing from JDK trust store)"
    } elseif ($outputText -match 'UnknownHost|NoRouteToHost|ConnectException|timed out') {
        $result.Status = 'gradle-fail'; $result.FailKind = 'network'
        $result.Message = "Real-build probe could not reach repo.maven.apache.org / plugins.gradle.org -- proxy not configured or mirror needed"
    } else {
        $result.Status = 'gradle-fail'; $result.FailKind = 'other'
        $result.Message = "Real-build probe FAILED (exit $exitCode)"
    }
    return $result
}

# Probe a SPECIFIC candidate URL as a Maven mirror. Different intent
# from Probe-RealBuildConfig: this answers "would THIS particular URL
# work if I wired it as a mavenCentral() override?" Useful when picking
# between multiple candidates from settings.xml.
function Probe-MirrorUrlViaGradle([string]$url, [string]$username = '', [string]$password = '') {
    $result = @{
        Status='fail'; Code=0; Message=''; WwwAuth=''; GetCode=$null
        AuthMode='gradle'; AuthPreview=''
    }
    # Locate a gradlew wrapper. Prefer one near the current working dir.
    $wrapper = $null
    $roots = @(
        (Get-Location).Path,
        (Split-Path (Get-Location).Path),
        (Join-Path (Get-Location).Path 'banking-app'),
        (Join-Path (Get-Location).Path 'bench-webui'),
        (Join-Path (Get-Location).Path 'bench-cli'),
        (Join-Path (Get-Location).Path 'bench-harness')
    )
    foreach ($root in $roots) {
        $candidate = Join-Path $root 'gradlew.bat'
        if (Test-Path $candidate) { $wrapper = $candidate; break }
    }
    if (-not $wrapper) {
        $result.Message = "no gradlew.bat wrapper found in nearby project dirs; cannot run Gradle probe"
        return $result
    }
    # Build a tiny probe project.
    $tmpdir = Join-Path $env:TEMP ("gradle-mirror-probe-" + [Guid]::NewGuid().ToString().Substring(0,8))
    [void](New-Item -ItemType Directory -Path $tmpdir -Force)
    $credsClause = ''
    if ($username -and $password -and $password -notmatch '^\s*\{.*\}\s*$' -and $password -notmatch '\$\{[^}]+\}') {
        $credsClause = "    credentials { username = `"$username`"; password = `"$password`" }"
    } elseif (-not $username -and $password -and $password -notmatch '^\s*\{.*\}\s*$' -and $password -notmatch '\$\{[^}]+\}') {
        $credsClause = @"
    credentials(HttpHeaderCredentials::class) {
        name = "Authorization"
        value = "Bearer $password"
    }
    authentication { create<HttpHeaderAuthentication>("header") }
"@
    }
    $build = @"
plugins { java }
repositories {
    // Keep the probe repo as the ONLY repo so resolution can't fall
    // back to a default mavenCentral() and give a misleading pass.
    maven {
        name = "probe"
        url = uri("$url")
        // Allow HTTPS-only probes against hosts Gradle 9 would
        // otherwise reject for cleartext; safer than disabling globally.
        isAllowInsecureProtocol = false
$credsClause
    }
}
configurations { create("mirrorProbe") }
dependencies {
    // Three representative artifacts that any Maven-Central-proxying repo
    // MUST serve for the build to work:
    //   - slf4j-api    (universal, every Java project pulls it)
    //   - gson         (transitive dep of the AppMap plugin -- the
    //                   classic "classpath failure" symptom when the
    //                   corp URL is libs-release instead of the
    //                   maven-central-virtual)
    //   - spring-boot  (proves Spring Boot resolution works for bench-webui)
    add("mirrorProbe", "org.slf4j:slf4j-api:2.0.16")
    add("mirrorProbe", "com.google.code.gson:gson:2.10.1")
    add("mirrorProbe", "org.springframework.boot:spring-boot:3.3.4")
}
tasks.register("checkMirror") {
    notCompatibleWithConfigurationCache("probe resolves eagerly")
    doLast {
        val files = configurations["mirrorProbe"].resolve()
        println("PROBE_OK: resolved " + files.size + " file(s)")
    }
}
"@
    Set-Content -Path (Join-Path $tmpdir 'settings.gradle.kts') -Value 'rootProject.name = "mirror-probe"'
    Set-Content -Path (Join-Path $tmpdir 'build.gradle.kts') -Value $build
    # Surface the native invocation so the operator can copy-paste it
    # to reproduce manually. Includes every path Gradle will read.
    $gradleUserHome = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $env:USERPROFILE '.gradle' }
    Write-Info "    Gradle probe command:"
    Write-Info "      `"$wrapper`" -p `"$tmpdir`" --refresh-dependencies --console=plain --no-configuration-cache --no-daemon --stacktrace checkMirror"
    Write-Info "    Gradle will read these properties files (in precedence order):"
    Write-Info "      1. $tmpdir\gradle.properties  (probe project; does not exist)"
    Write-Info "      2. $gradleUserHome\gradle.properties  (user-level; proxy + creds live here)"
    Write-Info "    Probe project files:"
    Write-Info "      $tmpdir\settings.gradle.kts"
    Write-Info "      $tmpdir\build.gradle.kts"
    Write-Info "    Init scripts applied (from $gradleUserHome\init.d\):"
    if (Test-Path (Join-Path $gradleUserHome 'init.d')) {
        Get-ChildItem (Join-Path $gradleUserHome 'init.d') -File -ErrorAction SilentlyContinue | ForEach-Object {
            Write-Info "      $($_.FullName)"
        }
    } else {
        Write-Info "      (none -- init.d directory does not exist)"
    }
    Write-Info "    JAVA_HOME: $(if ($env:JAVA_HOME) { $env:JAVA_HOME } else { '(not set; wrapper will resolve from PATH)' })"
    Write-Info "    Running probe (first run may take 30-60s while Gradle 9.4.1 distribution downloads)..."

    # Drop -q so we keep the actual error text; disable cache/daemon for
    # a clean one-shot; --stacktrace makes the exception shape visible.
    $output = & $wrapper -p $tmpdir `
        --refresh-dependencies `
        --console=plain `
        --no-configuration-cache `
        --no-daemon `
        --stacktrace `
        checkMirror 2>&1
    $exitCode = $LASTEXITCODE
    $outputText = ($output | Out-String)

    if ($exitCode -eq 0 -and $outputText -match 'PROBE_OK: resolved \d+') {
        # Clean up on success only -- keep the tmp project around on
        # failure so the operator can re-run manually.
        Remove-Item $tmpdir -Recurse -Force -ErrorAction SilentlyContinue
        $result.Status = 'reachable'; $result.Code = 200
        $result.Message = "Gradle resolved slf4j-api (exit 0)"
        return $result
    }
    # Failure path: leave tmpdir intact and tell the operator where it is.
    $result.TmpDir = $tmpdir

    # Extract diagnostic lines FIRST so every failure branch has them.
    # Prioritize Gradle's own "What went wrong:" block, then any
    # Could-not-resolve / handshake / fatal-alert lines, deduplicated.
    $diag = New-Object System.Collections.ArrayList
    $inWentWrong = $false
    foreach ($line in $output) {
        $s = $line.ToString()
        if ($s -match '^\s*\*\s*What went wrong:') { $inWentWrong = $true; continue }
        if ($inWentWrong -and $s -match '^\s*\*\s*Try:') { $inWentWrong = $false }
        if ($inWentWrong -and $s.Trim()) { [void]$diag.Add($s.Trim()) }
    }
    foreach ($line in $output) {
        $s = $line.ToString()
        if ($s -match 'Could not resolve|Received fatal alert|Failed to transfer|Could not [Gg]et|Could not HEAD') {
            if ($diag -notcontains $s.Trim()) { [void]$diag.Add($s.Trim()) }
        }
    }
    $result.DiagLines = @($diag | Select-Object -First 6)

    # Categorize the failure. Use 'gradle-fail' so the candidate-probe
    # switch knows to render the Gradle-specific diagnostics rather
    # than the curl-only fields (WwwAuth, BearerRetryCode, GetCode).
    if ($outputText -match '401|[Uu]nauthorized') {
        $result.Status = 'gradle-fail'
        $result.Message = "Gradle got HTTP 401 (auth rejected)"
        $result.FailKind = 'auth'
    } elseif ($outputText -match '403|[Ff]orbidden') {
        $result.Status = 'gradle-fail'
        $result.Message = "Gradle got HTTP 403 (forbidden)"
        $result.FailKind = 'auth'
    } elseif ($outputText -match 'PKIX|SSLHandshake|trust|certificate') {
        $result.Status = 'gradle-fail'
        $result.Message = "Gradle TLS handshake failed (likely corporate CA not in JDK trust store)"
        $result.FailKind = 'tls'
    } elseif ($outputText -match 'UnknownHost|NoRouteToHost|ConnectException|timed out') {
        $result.Status = 'gradle-fail'
        $result.Message = "Gradle could not reach host (network / proxy / DNS)"
        $result.FailKind = 'network'
    } elseif ($outputText -match 'com\.google\.code\.gson|spring-boot' -and $outputText -match 'Could not (?:find|resolve)') {
        $result.Status = 'gradle-fail'
        $result.Message = "Gradle could not find common Maven Central artifacts at this URL"
        $result.FailKind = 'wrong-repo-type'
    } else {
        $result.Status = 'gradle-fail'
        $result.Message = "Gradle FAILED (exit $exitCode)"
        $result.FailKind = 'other'
    }
    return $result
}

# Probe a candidate mirror URL with a HEAD request. Returns a hashtable
# with Status ('reachable' / 'unauth' / 'tls' / 'fail'), Code, Message
# AND, when auth is rejected: WwwAuth (the WWW-Authenticate header text
# so the operator sees which auth scheme the server is demanding),
# GetCode (retry with GET -- some Artifactory configs reject HEAD but
# allow GET for the same URL), AuthMode ('basic' / 'bearer' / 'none' --
# which one we sent), and AuthPreview (username + masked-password tail
# so the operator can confirm the right cred is being sent without
# leaking the secret).
function Probe-MirrorUrl([string]$url, [string]$username = '', [string]$password = '') {
    $authMode = 'none'
    $authPreview = ''
    $headers = @{}
    if ($username -and $password -and $password -notmatch '^\s*\{.*\}\s*$' -and $password -notmatch '\$\{[^}]+\}') {
        $pair = "$username`:$password"
        $headers['Authorization'] = 'Basic ' + [Convert]::ToBase64String(
            [System.Text.Encoding]::UTF8.GetBytes($pair))
        $authMode = 'basic'
        $pwLen = $password.Length
        $pwTail = if ($pwLen -ge 4) { $password.Substring($pwLen - 4) } else { '****' }
        $authPreview = "user='$username', password=[$pwLen chars, ending '...$pwTail']"
    } elseif ((-not $username) -and $password -and $password -notmatch '^\s*\{.*\}\s*$' -and $password -notmatch '\$\{[^}]+\}') {
        # Token-only: some Artifactory servers want Bearer, not Basic.
        $headers['Authorization'] = "Bearer $password"
        $authMode = 'bearer'
        $pwLen = $password.Length
        $pwTail = if ($pwLen -ge 4) { $password.Substring($pwLen - 4) } else { '****' }
        $authPreview = "Bearer token=[$pwLen chars, ending '...$pwTail']"
    }

    $result = @{
        Status='fail'; Code=0; Message=''
        WwwAuth=''; GetCode=$null; AuthMode=$authMode; AuthPreview=$authPreview
    }

    try {
        $r = Invoke-WebRequest -Uri $url -Method Head -TimeoutSec 15 -UseBasicParsing `
            -Headers $headers -ErrorAction Stop
        $result.Code = $r.StatusCode
        if ($r.StatusCode -in 200, 301, 302, 307) {
            $result.Status='reachable'; $result.Message="HTTP $($r.StatusCode)"
            return $result
        } elseif ($r.StatusCode -in 401, 403) {
            $result.Status='unauth'
            $result.Message = "HTTP $($r.StatusCode) -- reachable but requires credentials"
            $result.WwwAuth = $r.Headers['WWW-Authenticate']
        } else {
            $result.Message = "unexpected HTTP $($r.StatusCode)"
            return $result
        }
    } catch [System.Net.WebException] {
        $msg = $_.Exception.Message
        $resp = $_.Exception.Response
        if ($resp -and $resp.StatusCode) {
            $code = [int]$resp.StatusCode
            $result.Code = $code
            if ($code -in 401, 403) {
                $result.Status='unauth'
                $result.Message = "HTTP $code -- reachable but requires credentials"
                # Invoke-WebRequest puts the WWW-Authenticate header on the response object.
                try {
                    $wa = $resp.Headers['WWW-Authenticate']
                    if ($wa) { $result.WwwAuth = $wa }
                } catch { }
            } else {
                $result.Status='fail'; $result.Message = "HTTP $code"
                return $result
            }
        } elseif ($msg -match 'trust relationship|SSL|certificate|TLS') {
            $result.Status='tls'; $result.Message="TLS handshake failed -- corporate root may need import (see TLS chain section below)"
            return $result
        } else {
            $result.Status='fail'; $result.Message=$msg
            return $result
        }
    } catch {
        $result.Status='fail'; $result.Message = $_.Exception.Message
        return $result
    }

    # 401/403 recovery cascade, in order:
    #   (a) If we sent Basic and the server's WWW-Authenticate says
    #       Bearer, retry using the same password as a Bearer token.
    #       Artifactory's Identity Tokens / Access Tokens are commonly
    #       deployed this way and can reject Basic even when the token
    #       is valid.
    #   (b) If still failing, retry with GET (some Artifactory configs
    #       reject HEAD on repo URLs but accept GET).
    if ($result.Status -eq 'unauth') {
        $serverWantsBearer = ($result.WwwAuth -match '^\s*Bearer\b')
        if ($authMode -eq 'basic' -and $password -and ($serverWantsBearer -or $result.WwwAuth -eq '')) {
            try {
                $bearerHeaders = @{ 'Authorization' = "Bearer $password" }
                $rb = Invoke-WebRequest -Uri $url -Method Head -TimeoutSec 15 -UseBasicParsing `
                    -Headers $bearerHeaders -ErrorAction Stop
                if ($rb.StatusCode -in 200, 301, 302, 307) {
                    $result.Status='reachable'
                    $result.Code = $rb.StatusCode
                    $result.Message = "HTTP $($rb.StatusCode) via Bearer retry (Basic rejected; Artifactory Identity-Token style)"
                    $result.AuthMode = 'basic+bearer-retry'
                    return $result
                }
            } catch [System.Net.WebException] {
                # Bearer also rejected; fall through to GET retry below.
                $br = $_.Exception.Response
                if ($br -and $br.StatusCode) {
                    $result.BearerRetryCode = [int]$br.StatusCode
                }
            } catch {
                $result.BearerRetryCode = 0
            }
        }
        try {
            $rg = Invoke-WebRequest -Uri $url -Method Get -TimeoutSec 15 -UseBasicParsing `
                -Headers $headers -ErrorAction Stop
            $result.GetCode = $rg.StatusCode
            if ($rg.StatusCode -in 200, 301, 302, 307) {
                # GET succeeds where HEAD fails -- typical Artifactory behavior.
                $result.Status='reachable'
                $result.Message = "HTTP $($rg.StatusCode) via GET (server rejected HEAD)"
            }
        } catch [System.Net.WebException] {
            $getResp = $_.Exception.Response
            if ($getResp -and $getResp.StatusCode) {
                $result.GetCode = [int]$getResp.StatusCode
            }
        } catch {
            $result.GetCode = 0
        }
    }
    return $result
}

function Scan-Directory([string]$dir, [int]$depth = 3) {
    if (-not (Test-Path $dir)) { Write-Warn "Directory does not exist: $dir"; return }
    Write-Info "Recursively searching $dir for settings.xml / maven-wrapper.properties (depth $depth)"
    $found = Get-ChildItem -Path $dir -Recurse -Depth $depth -ErrorAction SilentlyContinue `
        -Include 'settings.xml', 'maven-wrapper.properties' -File
    if (-not $found) { Write-Warn "No settings.xml or maven-wrapper.properties found under $dir"; return }
    foreach ($f in $found) {
        Write-Info "  -> $($f.FullName)"
        if ($f.Name -eq 'settings.xml') { Parse-SettingsXml $f.FullName }
        else                            { Parse-MavenWrapper $f.FullName }
    }
}

function Scan-DefaultLocations {
    Write-Info "Scanning default locations:"
    foreach ($p in $defaultSettings + $defaultWrappers) { Write-Info "  $p" }
    foreach ($p in $defaultSettings) { Parse-SettingsXml $p }
    foreach ($p in $defaultWrappers) { Parse-MavenWrapper $p }
}

# Dispatch --------------------------------------------------------------------
# Always do a quick pre-scan of ~/.m2/settings.xml -- that's Maven's
# canonical location, and many enterprises drop the baseline config
# there. If we find something, we'll still offer additional scans so
# application-specific repos (documented as "append any additional
# repositories that need to be referenced") can come from elsewhere.
$m2Default = Join-Path $env:USERPROFILE '.m2\settings.xml'
$m2HadContent = $false
if (Test-Path $m2Default) {
    Write-Info "Found enterprise default at $m2Default -- parsing first."
    $countBefore = $script:mavenHints.Count
    Parse-SettingsXml $m2Default
    $m2HadContent = ($script:mavenHints.Count -gt $countBefore)
}

if ($MavenConfigPath) {
    Write-Info "Honoring -MavenConfigPath = $MavenConfigPath"
    if (Test-Path $MavenConfigPath) {
        $item = Get-Item $MavenConfigPath
        if ($item.PSIsContainer) {
            Scan-Directory $item.FullName
        } else {
            # Don't require the exact canonical name -- some orgs rename
            # the file when distributing (e.g. corp-settings.xml).
            # Dispatch on extension; if neither matches, try both.
            switch -Regex ($item.Extension) {
                '\.xml$'        { Parse-SettingsXml  $item.FullName }
                '\.properties$' { Parse-MavenWrapper $item.FullName }
                default {
                    Write-Warn "File has no .xml or .properties extension; attempting both parsers..."
                    Parse-SettingsXml  $item.FullName
                    Parse-MavenWrapper $item.FullName
                }
            }
        }
    } else {
        Write-Warn "-MavenConfigPath does not exist: $MavenConfigPath"
    }
    # Also sweep the rest of the default locations so operator-supplied
    # + auto-detected hits combine into one findings list. (~/.m2/
    # already handled above.)
    foreach ($p in $defaultSettings | Where-Object { $_ -ne $m2Default }) { Parse-SettingsXml $p }
    foreach ($p in $defaultWrappers) { Parse-MavenWrapper $p }
} elseif ($NonInteractive) {
    # Unattended: default-locations only. ~/.m2/ already handled above.
    foreach ($p in $defaultSettings | Where-Object { $_ -ne $m2Default }) { Parse-SettingsXml $p }
    foreach ($p in $defaultWrappers) { Parse-MavenWrapper $p }
} else {
    # Interactive. If ~/.m2/settings.xml already gave us URLs,
    # short-circuit the "do you have one?" question -- yes, they
    # obviously do, and it's at the standard enterprise location. Just
    # offer the option to scan additional locations for project-specific
    # repos (which teams "append" per the enterprise convention).
    Write-Host ""
    if ($m2HadContent) {
        Write-Host "  Enterprise convention: settings.xml is placed at $m2Default" -ForegroundColor Cyan
        Write-Host "  Application teams may 'append any additional repositories that need to be"
        Write-Host "  referenced' -- either to that file or to a separate one elsewhere."
        $has = Read-Host "  Scan additional locations for more settings.xml / maven-wrapper.properties? [y/N]"
    } else {
        Write-Host "  Corporate networks often ship a settings.xml (placed at $m2Default) or a" -ForegroundColor Cyan
        Write-Host "  maven-wrapper.properties that tells Maven / Gradle where the internal"
        Write-Host "  Artifactory mirror lives. Nothing was found at the enterprise default location."
        $has = Read-Host "  Do you already have a settings.xml (or maven-wrapper.properties)? [y/N]"
    }
    if ($has -match '^(y|yes)$') {
        Write-Host ""
        Write-Host "  How should I find it?" -ForegroundColor Yellow
        Write-Host "    [a] Scan the Downloads folder  ($downloadsDir)"
        Write-Host "    [b] Specify a folder to recursively search (depth 3)"
        Write-Host "    [c] Specify the direct file path"
        Write-Host "    [d] Scan all default locations  (~/.m2/, ~/Downloads/, project root)"
        Write-Host "    [s] Skip"
        $opt = Read-Host "  Choose [a/b/c/d/s]"
        switch -regex ($opt) {
            '^a' { Scan-Directory $downloadsDir }
            '^b' {
                $dir = Read-Host "  Folder path"
                if ($dir) { Scan-Directory $dir }
            }
            '^c' {
                $file = Read-Host "  File path (settings.xml or maven-wrapper.properties)"
                if ($file -and (Test-Path $file)) {
                    $item = Get-Item $file
                    switch -Regex ($item.Extension) {
                        '\.xml$'        { Parse-SettingsXml  $item.FullName }
                        '\.properties$' { Parse-MavenWrapper $item.FullName }
                        default {
                            Write-Warn "File has no .xml or .properties extension; attempting both parsers..."
                            Parse-SettingsXml  $item.FullName
                            Parse-MavenWrapper $item.FullName
                        }
                    }
                } elseif ($file) {
                    Write-Warn "File does not exist: $file"
                }
            }
            '^d' { Scan-DefaultLocations }
            default { Write-Info "Skipped additional Maven config scan." }
        }
    } elseif ($m2HadContent) {
        Write-Info "Using only the enterprise default at $m2Default."
    } else {
        Write-Info "Skipped (answered no). If Maven Central turns out to be unreachable below, re-run and answer yes."
    }
}

# Note: up to this point we've only PARSED settings.xml. The next
# section offers to WRITE a proactive 'artifactory-external-mirror'
# configuration, the canonical enterprise reference setup. It produces
# matching entries in ~/.m2/settings.xml (<mirror> + <server>),
# ~/.gradle/gradle.properties (Gradle credential aliases + proxy creds),
# and ~/.gradle/init.d/corp-repos.gradle.kts. After it runs, the Maven
# Central reachability check below tests the wired-up mirror end-to-end.

# --- Artifactory external-mirror proactive setup ----------------------------
Write-Section "Artifactory external-mirror setup"

$settingsXmlAm = Join-Path $env:USERPROFILE '.m2\settings.xml'
$artifactoryMirrorId = 'artifactory-external-mirror'
$initDirAm    = Join-Path $env:USERPROFILE '.gradle\init.d'
$initScriptAm = Join-Path $initDirAm 'corp-repos.gradle.kts'

# State detection -- have we already wired this id end-to-end?
$haveSettingsMirror = $false
$haveSettingsServer = $false
$haveInitScriptAm   = Test-Path $initScriptAm
if (Test-Path $settingsXmlAm) {
    $sxAm = Get-Content -Raw -LiteralPath $settingsXmlAm -ErrorAction SilentlyContinue
    if ($sxAm) {
        $idEsc = [regex]::Escape($artifactoryMirrorId)
        foreach ($mm in [regex]::Matches($sxAm, '(?s)<mirror>(.*?)</mirror>')) {
            if ($mm.Groups[1].Value -match "<id>\s*$idEsc\s*</id>") { $haveSettingsMirror = $true; break }
        }
        foreach ($mm in [regex]::Matches($sxAm, '(?s)<server>(.*?)</server>')) {
            if ($mm.Groups[1].Value -match "<id>\s*$idEsc\s*</id>") { $haveSettingsServer = $true; break }
        }
    }
}

if ($haveSettingsMirror -and $haveSettingsServer -and $haveInitScriptAm) {
    Write-Pass "$artifactoryMirrorId already wired in $settingsXmlAm + ~/.gradle/init.d/."
    Write-Info "  To reconfigure, delete the matching <mirror>/<server> blocks (or the init script) and re-run."
} elseif ($NonInteractive) {
    Write-Info "Non-interactive mode: skipping $artifactoryMirrorId setup prompt."
} else {
    Write-Host ""
    Write-Host "  Many enterprise reference configurations name their Maven Central proxy" -ForegroundColor Cyan
    Write-Host "  '$artifactoryMirrorId'. If you have that URL, this script can wire it" -ForegroundColor Cyan
    Write-Host "  into settings.xml (mirror + server), gradle.properties, and an init script." -ForegroundColor Cyan
    if ($haveSettingsMirror) { Write-Info "  Existing: <mirror id='$artifactoryMirrorId'> in $settingsXmlAm" }
    if ($haveSettingsServer) { Write-Info "  Existing: <server id='$artifactoryMirrorId'> in $settingsXmlAm" }
    if ($haveInitScriptAm)   { Write-Info "  Existing: $initScriptAm (will be replaced if you proceed)" }

    # Default URL from any candidate the parser already discovered.
    $suggestedUrl = ''
    if ($script:mavenHints -and $script:mavenHints.Count -gt 0) {
        $suggestedUrl = $script:mavenHints[0].Url
    }

    $promptUrl = if ($suggestedUrl) {
        "  Enter the artifactory-external-mirror URL [default: $suggestedUrl] (blank to skip)"
    } else {
        "  Enter the artifactory-external-mirror URL (blank to skip)"
    }
    $amUrl = Read-Host $promptUrl
    if (-not $amUrl) { $amUrl = $suggestedUrl }

    if (-not $amUrl) {
        Write-Info "Skipped (no URL provided)."
    } else {
        if (-not $amUrl.EndsWith('/')) { $amUrl = "$amUrl/" }

        # Pull existing proxy creds as defaults so the user can hit ENTER
        # twice if they're already configured.
        $proxyUserDefault = ''
        $proxyPassDefault = ''
        if (Test-Path $gradleProps) {
            $gpRaw = Get-Content -LiteralPath $gradleProps
            $userLine = $gpRaw | Where-Object { $_ -match '^\s*systemProp\.https\.proxyUser\s*=' } | Select-Object -First 1
            $passLine = $gpRaw | Where-Object { $_ -match '^\s*systemProp\.https\.proxyPassword\s*=' } | Select-Object -First 1
            if ($userLine) { $proxyUserDefault = ($userLine -replace '^[^=]+=\s*','').Trim() }
            if ($passLine) { $proxyPassDefault = ($passLine -replace '^[^=]+=\s*','').Trim() }
        }

        $userPrompt = if ($proxyUserDefault) {
            "  Proxy username [default: $proxyUserDefault]"
        } else {
            "  Proxy username"
        }
        $amUser = Read-Host $userPrompt
        if (-not $amUser) { $amUser = $proxyUserDefault }

        $passPrompt = if ($proxyPassDefault) {
            "  Proxy password (input hidden) [press ENTER to reuse the password already in $gradleProps]"
        } else {
            "  Proxy password (input hidden)"
        }
        $secPwd = Read-Host -AsSecureString $passPrompt
        $bstr = [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($secPwd)
        try {
            $amPass = [System.Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
        } finally {
            [System.Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
        }
        if (-not $amPass) { $amPass = $proxyPassDefault }

        if (-not $amUser -or -not $amPass) {
            Write-Warn "Username or password is empty -- aborting mirror setup." `
                "Re-run with proxy credentials to hand. Nothing has been written."
        } else {
            Write-Host ""
            Write-Host "  About to write/update:" -ForegroundColor Yellow
            Write-Host "    1. $settingsXmlAm"
            Write-Host "         <server id='$artifactoryMirrorId'> + <mirror id='$artifactoryMirrorId' mirrorOf='*'>"
            Write-Host "    2. $gradleProps"
            Write-Host "         orgInternalMavenUser/Password (+ wrapper, resolver, http(s) proxy aliases)"
            Write-Host "    3. $initScriptAm"
            Write-Host "         redirects mavenCentral() / gradlePluginPortal() to $amUrl"
            Write-Host "  Existing files will be backed up to .backup-<timestamp> siblings."
            $resp = Read-Host "  Proceed? [y/N]"
            if ($resp -match '^[Yy]') {
                # 1. settings.xml -- BACKUP-BEFORE-WRITE: if we can't
                #    successfully copy an existing file to a sibling
                #    .backup-*, abort the entire mirror setup so no one
                #    ends up with a modified settings.xml and no rollback.
                $settingsDir = Split-Path -Parent $settingsXmlAm
                if (-not (Test-Path $settingsDir)) {
                    New-Item -ItemType Directory -Path $settingsDir -Force | Out-Null
                }
                $backupOk = $true
                $settingsBackup = ''
                if (Test-Path $settingsXmlAm) {
                    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
                    $settingsBackup = "$settingsXmlAm.backup-$stamp"
                    try {
                        Copy-Item -LiteralPath $settingsXmlAm -Destination $settingsBackup -Force -ErrorAction Stop
                        if ((Test-Path $settingsBackup) -and ((Get-Item $settingsBackup).Length -gt 0)) {
                            Write-Pass "Backed up $settingsXmlAm to $settingsBackup"
                        } else {
                            $backupOk = $false
                        }
                    } catch {
                        $backupOk = $false
                    }
                    if (-not $backupOk) {
                        Write-Fail "Could not back up $settingsXmlAm to $settingsBackup -- aborting mirror setup; no files were modified." `
                            "Check write perms on $settingsDir and free disk space, then re-run."
                    }
                }

                if ($backupOk) {
                    $newServer = @"
    <server>
      <id>$artifactoryMirrorId</id>
      <username>$amUser</username>
      <password>$amPass</password>
    </server>
"@
                    $newMirror = @"
    <mirror>
      <id>$artifactoryMirrorId</id>
      <mirrorOf>*</mirrorOf>
      <name>Corporate Maven Central proxy (artifactory-external-mirror)</name>
      <url>$amUrl</url>
    </mirror>
"@

                    if (Test-Path $settingsXmlAm) {
                        $txt = Get-Content -Raw -LiteralPath $settingsXmlAm
                    } else {
                        $txt = @"
<?xml version="1.0" encoding="UTF-8"?>
<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.2.0 https://maven.apache.org/xsd/settings-1.2.0.xsd">
</settings>
"@
                    }

                    # Strip any prior <server>/<mirror> blocks carrying our id
                    # (idempotent re-run). Done with an explicit Match loop +
                    # StringBuilder to avoid the scriptblock-to-MatchEvaluator
                    # coercion path, whose behavior varies across PowerShell
                    # 5.1 vs 7 in some configurations.
                    $idEscW    = [regex]::Escape($artifactoryMirrorId)
                    $idPattern = "<id>\s*$idEscW\s*</id>"
                    foreach ($tag in @('server','mirror')) {
                        $outerRx = [regex]"(?s)\s*<$tag>(.*?)</$tag>"
                        $sb = New-Object System.Text.StringBuilder
                        $lastEnd = 0
                        foreach ($m in $outerRx.Matches($txt)) {
                            [void]$sb.Append($txt.Substring($lastEnd, $m.Index - $lastEnd))
                            if (-not ($m.Groups[1].Value -match $idPattern)) {
                                [void]$sb.Append($m.Value)
                            }
                            $lastEnd = $m.Index + $m.Length
                        }
                        [void]$sb.Append($txt.Substring($lastEnd))
                        $txt = $sb.ToString()
                    }

                    # Insert into <servers>/<mirrors>; create the parent if missing; create inside <settings>.
                    function Insert-IntoParent($parentTag, $childBlock, $text) {
                        $closeTag = "</$parentTag>"
                        if ($text.Contains($closeTag)) {
                            $idx = $text.IndexOf($closeTag)
                            return $text.Substring(0, $idx) + "$childBlock`n  " + $text.Substring($idx)
                        }
                        $block = "  <$parentTag>`n$childBlock`n  </$parentTag>`n"
                        if ($text.Contains('</settings>')) {
                            $idx = $text.IndexOf('</settings>')
                            return $text.Substring(0, $idx) + $block + $text.Substring($idx)
                        }
                        return $text + $block
                    }
                    $txt = Insert-IntoParent 'servers' $newServer $txt
                    $txt = Insert-IntoParent 'mirrors' $newMirror $txt

                    # Write UTF-8 (no BOM) to keep settings.xml clean for Maven.
                    [System.IO.File]::WriteAllText($settingsXmlAm, $txt, (New-Object System.Text.UTF8Encoding($false)))

                    # Tighten ACL: remove inheritance, grant only current user.
                    try {
                        $acl = Get-Acl -LiteralPath $settingsXmlAm
                        $acl.SetAccessRuleProtection($true, $false)
                        $rule = New-Object System.Security.AccessControl.FileSystemAccessRule(
                            [System.Security.Principal.WindowsIdentity]::GetCurrent().Name,
                            'FullControl','Allow')
                        $acl.SetAccessRule($rule)
                        Set-Acl -LiteralPath $settingsXmlAm -AclObject $acl
                    } catch {
                        Write-Warn "Could not tighten ACL on $settingsXmlAm ($_)"
                    }
                    Write-Pass "Wrote <server> + <mirror> id='$artifactoryMirrorId' to $settingsXmlAm (ACL tightened to current user)"

                    # 2. gradle.properties -- back up, strip prior keys we own, append fresh.
                    if (Test-Path $gradleProps) {
                        $stampGp = Get-Date -Format 'yyyyMMdd-HHmmss'
                        Copy-Item -LiteralPath $gradleProps -Destination "$gradleProps.backup-$stampGp" -Force
                        Write-Pass "Backed up $gradleProps to $gradleProps.backup-$stampGp"
                    } else {
                        $gpDir = Split-Path -Parent $gradleProps
                        if (-not (Test-Path $gpDir)) {
                            New-Item -ItemType Directory -Path $gpDir -Force | Out-Null
                        }
                    }
                    $gpKeep = @()
                    if (Test-Path $gradleProps) {
                        $gpKeep = Get-Content -LiteralPath $gradleProps | Where-Object {
                            $_ -notmatch '^\s*(orgInternalMaven(User|Password)|systemProp\.gradle\.wrapper(User|Password)|artifactoryResolver(Username|Password)|systemProp\.https?\.proxy(User|Password))\s*='
                        }
                    }
                    $now = Get-Date -Format 'yyyy-MM-ddTHH:mm:ss'
                    $gpAppend = @(
                        ''
                        "# Added by build-health-check.ps1 on $now -- artifactory-external-mirror."
                        "# Same username/password drives BOTH the corporate proxy AND the Artifactory mirror."
                        "orgInternalMavenUser=$amUser"
                        "orgInternalMavenPassword=$amPass"
                        "systemProp.gradle.wrapperUser=$amUser"
                        "systemProp.gradle.wrapperPassword=$amPass"
                        "artifactoryResolverUsername=$amUser"
                        "artifactoryResolverPassword=$amPass"
                        "systemProp.https.proxyUser=$amUser"
                        "systemProp.https.proxyPassword=$amPass"
                        "systemProp.http.proxyUser=$amUser"
                        "systemProp.http.proxyPassword=$amPass"
                    )
                    Set-Content -LiteralPath $gradleProps -Value (($gpKeep + $gpAppend) -join "`r`n")
                    try {
                        $aclGp = Get-Acl -LiteralPath $gradleProps
                        $aclGp.SetAccessRuleProtection($true, $false)
                        $ruleGp = New-Object System.Security.AccessControl.FileSystemAccessRule(
                            [System.Security.Principal.WindowsIdentity]::GetCurrent().Name,
                            'FullControl','Allow')
                        $aclGp.SetAccessRule($ruleGp)
                        Set-Acl -LiteralPath $gradleProps -AclObject $aclGp
                    } catch {
                        Write-Warn "Could not tighten ACL on $gradleProps ($_)"
                    }
                    Write-Pass "Wrote credential aliases (3 Gradle pairs + 2 proxy pairs) to $gradleProps (ACL tightened to current user)"

                    # 3. init.d/corp-repos.gradle.kts -- references creds via
                    #    providers.gradleProperty so values stay in gradle.properties.
                    if (-not (Test-Path $initDirAm)) {
                        New-Item -ItemType Directory -Path $initDirAm -Force | Out-Null
                    }
                    if (Test-Path $initScriptAm) {
                        $stampIs = Get-Date -Format 'yyyyMMdd-HHmmss'
                        Copy-Item -LiteralPath $initScriptAm -Destination "$initScriptAm.backup-$stampIs" -Force
                        Write-Pass "Backed up $initScriptAm to $initScriptAm.backup-$stampIs"
                    }
                    $initContentAm = @"
// Added by build-health-check.ps1 on $now.
// Routes mavenCentral() + gradlePluginPortal() through the corporate
// artifactory-external-mirror. Credentials are loaded from
// ~/.gradle/gradle.properties (NOT committed). To revert: delete this file.
val corpMavenUrl = "$amUrl"

fun maybeRewrite(repo: ArtifactRepository) {
    if (repo is MavenArtifactRepository) {
        val u = repo.url.toString()
        if (u.contains("repo.maven.apache.org") ||
            u.contains("plugins.gradle.org") ||
            u.contains("repo1.maven.org")) {
            repo.setUrl(corpMavenUrl)
            repo.credentials {
                username = providers.gradleProperty("orgInternalMavenUser").get()
                password = providers.gradleProperty("orgInternalMavenPassword").get()
            }
        }
    }
}

settingsEvaluated {
    pluginManagement.repositories.configureEach { maybeRewrite(this) }
    dependencyResolutionManagement.repositories.configureEach { maybeRewrite(this) }
}

allprojects {
    buildscript.repositories.configureEach { maybeRewrite(this) }
    repositories.configureEach { maybeRewrite(this) }
}
"@
                    Set-Content -LiteralPath $initScriptAm -Value $initContentAm
                    Write-Pass "Wrote $initScriptAm  ->  redirects mavenCentral() / gradlePluginPortal() to $amUrl"
                    Write-Info "All three '$artifactoryMirrorId' configuration elements are in place."
                    Write-Info "The Maven Central reachability check below will probe the wired-up mirror end-to-end."
                }
            } else {
                Write-Info "Skipped. No files were modified."
            }
        }
    }
}

# --- Gradle init-script shape verification ---------------------------------
# Runs UNCONDITIONALLY. If the user has an existing corp-repos.gradle.kts
# from a prior health-check run, check that it's still correct for the
# current Gradle version. Older templates missed settings-time plugin
# resolution -- fine for Gradle 8.x, broken for 9.x (which is where we
# are after the wrapper bump). Without this check, the user sees "Gradle
# can't resolve com.google.code.gson" and has to debug it themselves.
$initDir = Join-Path $env:USERPROFILE '.gradle\init.d'
$initScript = Join-Path $initDir 'corp-repos.gradle.kts'
if (Test-Path $initScript) {
    $initContent = Get-Content $initScript -Raw
    $hasSettingsEvaluated = $initContent -match 'settingsEvaluated\s*\{'
    $hasPluginsGradleOrg  = $initContent -match 'plugins\.gradle\.org'
    $hasHelperFn          = $initContent -match 'fun\s+maybeRewrite'
    $isCurrent = $hasSettingsEvaluated -and $hasPluginsGradleOrg -and $hasHelperFn

    if ($isCurrent) {
        Write-Pass "Existing init script at $initScript is up-to-date (Gradle 9.x compatible)."
    } else {
        Write-Warn "Existing init script at $initScript is OUTDATED." `
            "Missing coverage for settings-time plugin resolution and/or plugins.gradle.org. Gradle 9.x builds will fail to resolve plugin transitive dependencies (com.google.code.gson is a common symptom)."
        # Surface exactly what's missing.
        if (-not $hasSettingsEvaluated) { Write-Info "  [MISSING] settingsEvaluated hook -- plugin resolution goes unrewritten." }
        if (-not $hasPluginsGradleOrg)  { Write-Info "  [MISSING] plugins.gradle.org redirect -- Gradle Plugin Portal isn't routed through corp mirror." }
        if (-not $hasHelperFn)          { Write-Info "  [MISSING] helper function style -- old template structure." }

        if (-not $NonInteractive) {
            # Extract the existing URL + auth style so the regen preserves them.
            $existingUrl = $null
            if ($initContent -match 'val\s+corpMavenUrl\s*=\s*"([^"]+)"') {
                $existingUrl = $Matches[1]
            }
            if (-not $existingUrl) {
                Write-Warn "Could not extract corpMavenUrl from the old init script; you'll have to rerun with fresh input."
            } else {
                $useBearer = ($initContent -match 'HttpHeaderCredentials')
                $useBasic  = ($initContent -match '(?m)^\s*credentials\s*\{[\s\S]*?gradleProperty') -and -not $useBearer
                $authDesc  = if ($useBearer) { 'Bearer (HttpHeaderAuthentication)' } elseif ($useBasic) { 'Basic (username + password)' } else { 'no credentials' }
                Write-Host ""
                Write-Host "  The new template will preserve your existing settings:" -ForegroundColor Yellow
                Write-Host "    URL:    $existingUrl"
                Write-Host "    Auth:   $authDesc"
                Write-Host "    (credentials live in gradle.properties; the init script only references them)"
                $resp = Read-Host "  Regenerate $initScript with the current template? [y/N]"
                if ($resp -match '^(y|yes)$') {
                    # Build the creds clause from the detected auth style.
                    $regenCreds = if ($useBearer) { @'

            repo.credentials(HttpHeaderCredentials::class) {
                name = "Authorization"
                value = "Bearer " + providers.gradleProperty("orgInternalMavenPassword").get()
            }
            repo.authentication {
                create<HttpHeaderAuthentication>("header")
            }
'@ } elseif ($useBasic) { @'

            repo.credentials {
                username = providers.gradleProperty("orgInternalMavenUser").get()
                password = providers.gradleProperty("orgInternalMavenPassword").get()
            }
'@ } else { '' }
                    $regenContent = @"
// Regenerated by build-health-check.ps1 on $(Get-Date -Format 's').
// Redirects mavenCentral() AND gradlePluginPortal() to the corporate
// proxy across every resolution scope Gradle uses:
//   - settings.pluginManagement.repositories   (plugin resolution)
//   - settings.dependencyResolutionManagement  (centralized repos)
//   - allprojects.buildscript.repositories     (per-project plugins)
//   - allprojects.repositories                 (project dependencies)
// To revert: restore the .backup-* sibling of this file.
val corpMavenUrl = "$existingUrl"

fun maybeRewrite(repo: ArtifactRepository) {
    if (repo is MavenArtifactRepository) {
        val u = repo.url.toString()
        if (u.contains("repo.maven.apache.org") ||
            u.contains("plugins.gradle.org") ||
            u.contains("repo1.maven.org")) {
            repo.setUrl(corpMavenUrl)$regenCreds
        }
    }
}

settingsEvaluated {
    pluginManagement.repositories.configureEach { maybeRewrite(this) }
    dependencyResolutionManagement.repositories.configureEach { maybeRewrite(this) }
}

allprojects {
    buildscript.repositories.configureEach { maybeRewrite(this) }
    repositories.configureEach { maybeRewrite(this) }
}
"@
                    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
                    Copy-Item $initScript "$initScript.backup-$stamp" -Force
                    Write-Pass "Backed up old init script to $initScript.backup-$stamp"
                    [System.IO.File]::WriteAllText($initScript, $regenContent, (New-Object System.Text.UTF8Encoding($false)))
                    Write-Pass "Regenerated $initScript with current template (auth preserved: $authDesc)."
                } else {
                    Write-Info "Skipped. Plugin-resolution failures (classpath errors on transitive deps) will continue until the init script is updated."
                }
            }
        }
    }
}

if ($mavenHints.Count -gt 0) {
    Write-Pass "Found $($mavenHints.Count) candidate mirror URL(s):"
    for ($i = 0; $i -lt $mavenHints.Count; $i++) {
        $h = $mavenHints[$i]
        Write-Info ("  [{0}] {1}   ({2}, {3})" -f ($i + 1), $h.Url, $h.Source, $h.Note)
    }

    # Ask the operator whether to probe and how. The "how" matters:
    # curl uses .NET's HTTP stack + Windows cert store; Gradle uses
    # the JVM HTTP stack + JDK cacerts + the user's gradle.properties
    # proxy/creds. A URL that's reachable via curl may fail via Gradle
    # (different proxy, different trust store) and vice-versa. The
    # closer-to-actual-build probe is Gradle, so offer both.
    Write-Host ""
    $probeMode = 'r'
    if (-not $NonInteractive) {
        Write-Host "  Connectivity probe options:" -ForegroundColor Yellow
        Write-Host "    [r] Real build config (DEFAULT) -- mavenCentral() + gradlePluginPortal() just like"
        Write-Host "                           the actual build. Your proxy + init scripts apply naturally."
        Write-Host "                           Answers 'will .\gradlew.bat build work?' in ONE probe."
        Write-Host "                           No candidate URL is tested per se -- the REAL config is."
        Write-Host "    [g] Gradle per-candidate -- point a maven{} repo at EACH listed URL and probe."
        Write-Host "                           Useful when picking between multiple candidates."
        Write-Host "    [c] curl per-candidate -- fast HTTP probe of each listed URL."
        Write-Host "    [s] Skip"
        $pm = Read-Host "  Choose [r/g/c/s] (default: r)"
        $probeMode = if ($pm) { $pm.ToLower().Substring(0,1) } else { 'r' }
    }

    if ($probeMode -eq 's') {
        Write-Info "Skipped connectivity probes. Candidate URLs listed above; run .\gradlew.bat build --refresh-dependencies to test for real."
    } elseif ($probeMode -eq 'r') {
        # Single real-build probe -- one answer for the whole setup.
        Write-Info "Running ONE real-build probe (uses your actual Gradle config: proxy + init scripts + mavenCentral())..."
        $r = Probe-RealBuildConfig
        switch ($r.Status) {
            'reachable' {
                Write-Pass "Real build config RESOLVES successfully. .\gradlew.bat build will work."
                Write-Info "No mirror remediation needed -- your current proxy + init-script setup is sufficient."
            }
            'gradle-fail' {
                Write-Fail "Real build config FAILED: $($r.Message)"
                if ($r.DiagLines) {
                    Write-Info "  Gradle output (most relevant lines):"
                    foreach ($line in $r.DiagLines) { Write-Info "    $line" }
                }
                if ($r.TmpDir) {
                    Write-Info "  Probe project preserved at: $($r.TmpDir)"
                }
                switch ($r.FailKind) {
                    'auth'    { Write-Info "  -> Check orgInternalMaven* / artifactoryResolver* creds in $gradleProps." }
                    'tls'     { Write-Info "  -> Import the corporate root CA into the JDK trust store (TLS section below)." }
                    'network' { Write-Info "  -> Maven Central is unreachable. Either (a) configure a proxy in $gradleProps, or (b) wire a mirror via the Maven-Central remediation below." }
                    default   { Write-Info "  -> Re-run manually for stacktrace: cd <project> ; .\gradlew.bat --refresh-dependencies --stacktrace build" }
                }
            }
            default {
                Write-Fail "Real build config probe did not run: $($r.Message)"
            }
        }
    } else {
        Write-Info "Probing each candidate URL (method: $(if ($probeMode -eq 'g') { 'Gradle' } else { 'curl' }))..."
    }

    for ($i = 0; $i -lt $mavenHints.Count; $i++) {
        if ($probeMode -eq 's' -or $probeMode -eq 'r') { break }
        $h = $mavenHints[$i]
        $credKey = "$($h.Source)||$($h.RepoId)"
        $creds = $script:serverCreds[$credKey]
        $authNote = ''
        if ($creds -and $creds.Username) {
            $authNote = " (authenticated as '$($creds.Username)' from matching <server id='$($h.RepoId)'>)"
        }
        $r = if ($probeMode -eq 'g') {
            Probe-MirrorUrlViaGradle $h.Url ($creds?.Username) ($creds?.Password)
        } elseif ($creds -and ($creds.Username -or $creds.Password)) {
            Probe-MirrorUrl $h.Url $creds.Username $creds.Password
        } else {
            Probe-MirrorUrl $h.Url
        }
        switch ($r.Status) {
            'reachable' {
                Write-Pass "  [$($i + 1)] $($h.Url)  -->  $($r.Message)$authNote"
                if ($r.AuthMode -eq 'basic+bearer-retry') {
                    Write-Info "    NOTE: Basic auth was rejected; Bearer worked. The Gradle init script"
                    Write-Info "          will need to send the token as a Bearer header, not via the"
                    Write-Info "          usual credentials{} block. See the remediation step below."
                }
            }
            'gradle-fail' {
                # Gradle-specific rendering. Displays the actual Gradle
                # output lines instead of the curl-only diagnostic fields
                # (WwwAuth, BearerRetryCode, GetCode) which aren't
                # populated by the Gradle probe.
                Write-Fail "  [$($i + 1)] $($h.Url)  -->  $($r.Message)"
                if ($r.DiagLines) {
                    Write-Info "    Gradle output (most relevant lines):"
                    foreach ($line in $r.DiagLines) { Write-Info "      $line" }
                }
                if ($r.TmpDir) {
                    Write-Info "    Probe project preserved for inspection at: $($r.TmpDir)"
                }
                switch ($r.FailKind) {
                    'auth' {
                        if ($creds -and $creds.Encrypted) {
                            Write-Info "    -> settings.xml password is Maven-ENCRYPTED {...}; decrypt with 'mvn -ep' first."
                        } elseif ($creds -and $creds.Template) {
                            Write-Info "    -> settings.xml has a Maven template `${...} -- resolve via env var or paste literal."
                        } elseif ($creds -and $creds.Username) {
                            Write-Info "    -> Token from <server id='$($h.RepoId)'> (username '$($creds.Username)') was rejected."
                            Write-Info "       Regenerate via Artifactory 'Set Me Up -> Maven' and replace the password in settings.xml."
                        } else {
                            Write-Info "    -> No <server id='$($h.RepoId)'> creds attached. Add one in settings.xml or paste a user/token into $gradleProps."
                        }
                    }
                    'tls' {
                        Write-Info "    -> Import the corporate root CA into the JDK trust store (see TLS chain section below)."
                    }
                    'network' {
                        Write-Info "    -> Check VPN + proxy config in $gradleProps (proxyHost, proxyPort, nonProxyHosts)."
                    }
                    'wrong-repo-type' {
                        Write-Info "    -> This URL doesn't proxy Maven Central. Common JFrog Artifactory pattern:"
                        Write-Info "         libs-release          - your org's releases ONLY (no Maven Central proxy)"
                        Write-Info "         libs-release-virtual  - aggregates Maven Central + your org's libs (USE THIS)"
                        Write-Info "         maven-central-virtual - same idea under a different name"
                        Write-Info "       Find the right URL: in Artifactory, Repositories sidebar, filter Type=Virtual + PackageType=Maven."
                        Write-Info "       Or re-run 'Set Me Up -> Maven' and pick the virtual repo (not the release repo)."
                    }
                    default {
                        Write-Info "    -> Re-run Gradle manually for full stacktrace:"
                        Write-Info "       cd <project> ; .\gradlew.bat --refresh-dependencies --stacktrace help"
                    }
                }
            }
            'tls' {
                Write-Fail "  [$($i + 1)] $($h.Url)  -->  $($r.Message)"
                if ($r.DiagLines) {
                    foreach ($line in $r.DiagLines) { Write-Info "    $line" }
                }
                Write-Info "    -> Import the corporate root CA into the JDK trust store (see TLS chain section below)."
            }
            'fail' {
                Write-Fail "  [$($i + 1)] $($h.Url)  -->  $($r.Message)"
                if ($r.DiagLines) {
                    Write-Info "    Gradle output (most relevant lines):"
                    foreach ($line in $r.DiagLines) { Write-Info "      $line" }
                }
                Write-Info "    -> Re-run Gradle manually for full stacktrace:"
                Write-Info "       cd <project> ; .\gradlew.bat --refresh-dependencies --stacktrace help"
            }
            'unauth'    {
                Write-Fail "  [$($i + 1)] $($h.Url)  -->  $($r.Message)"
                # Always print the extra diagnostics so the operator
                # can see exactly why auth failed.
                Write-Info "    auth sent:         $($r.AuthMode)  ($($r.AuthPreview))"
                if ($r.WwwAuth) {
                    Write-Info "    server demands:    WWW-Authenticate: $($r.WwwAuth)"
                } else {
                    Write-Info "    server demands:    (no WWW-Authenticate header returned)"
                }
                if ($null -ne $r.BearerRetryCode) {
                    Write-Info "    Bearer retry:      HTTP $($r.BearerRetryCode) (still rejected; token itself is invalid or user-side scope is needed)"
                }
                if ($null -ne $r.GetCode) {
                    if ($r.GetCode -in 200, 301, 302, 307) {
                        Write-Info "    GET retry:         HTTP $($r.GetCode) -- GET SUCCEEDS where HEAD failed"
                        Write-Info "                       -> Gradle uses GET for resolution, so this URL will likely work"
                        Write-Info "                       at build time even though the HEAD probe above showed 401."
                    } else {
                        Write-Info "    GET retry:         HTTP $($r.GetCode) (same failure, not a HEAD-method issue)"
                    }
                }
                if ($creds -and $creds.Encrypted) {
                    Write-Info "    likely cause:      settings.xml password is Maven-ENCRYPTED {...}; decrypt with 'mvn -ep' + settings-security.xml and paste the plaintext back."
                } elseif ($creds -and $creds.Template) {
                    Write-Info "    likely cause:      settings.xml has a Maven template variable (e.g. \${env.TOKEN}) that curl can't resolve. Export that env var before running, or replace with literal in settings.xml."
                } elseif ($r.WwwAuth -match '^\s*Bearer\b') {
                    Write-Info "    likely cause:      Artifactory demands Bearer and the password used as a token was also rejected."
                    Write-Info "                       -> The token is invalid/expired. Regenerate via Set-Me-Up -> Maven."
                } elseif ($creds -and $creds.Username) {
                    Write-Info "    likely cause:      token/password expired, or the <server> creds are wrong."
                    Write-Info "                       -> Re-run 'Set Me Up -> Maven' in Artifactory to regenerate settings.xml with a fresh token."
                } else {
                    Write-Info "    likely cause:      no <server id='$($h.RepoId)'> in settings.xml. Add one or paste creds in gradle.properties."
                }
            }
            default     { Write-Fail "  [$($i + 1)] $($h.Url)  -->  $($r.Message)" }
        }
    }

    # Important -- this is the piece that was missing feedback. Make it
    # explicit that finding URLs is not the same as having Gradle use them.
    Write-Host ""
    Write-Info "IMPORTANT: the candidate URLs above are NOT wired into Gradle yet."
    Write-Info "Gradle still uses repo.maven.apache.org until ~/.gradle/init.d/corp-repos.gradle.kts"
    Write-Info "redirects it. If the Maven Central check below FAILs, the remediation prompt"
    Write-Info "will offer to write that init script using one of these candidates."
} elseif (-not $NonInteractive) {
    Write-Info "No corporate Maven config added to the candidate list. That's fine if Maven Central is directly reachable below."
}

# ---------------------------------------------------------------------------
# Reachability of artifact repositories
#
# Note on naming: "Maven Central" refers to the public Java artifact
# repository at repo.maven.apache.org -- not the Maven build tool.
# Gradle's mavenCentral() directive resolves to this same URL, so the
# project's Spring Boot / Kotlin / JUnit / Jackson artifacts all come
# from here at build time. If it's unreachable, `./gradlew build` fails
# regardless of which build tool (Gradle, Maven, sbt...) you use.
# ---------------------------------------------------------------------------
Write-Section "Artifact repository reachability"

$endpoints = @(
    @{ Name='Gradle distributions';              Url='https://services.gradle.org/distributions/' },
    @{ Name="Maven Central (Gradle's mavenCentral())"; Url='https://repo.maven.apache.org/maven2/' },
    @{ Name='Gradle Plugin Portal';              Url='https://plugins.gradle.org/m2/' },
    @{ Name='Foojay Disco (toolchain auto-download)'; Url='https://api.foojay.io/disco/v3.0/packages?version=17&vendor=temurin&architecture=x64&operating_system=windows&archive_type=zip&package_type=jdk&javafx_bundled=false&latest=available' },
    @{ Name='GitHub';                            Url='https://github.com/' }
)

$mavenCentralReachable = $true
$mavenCentralReachableViaProxy = $false
# Build the WebProxy that Gradle would actually use, so the probe
# matches Gradle's path (not WinINET's). Without this, the script
# can falsely report Maven Central unreachable when the corp proxy
# in gradle.properties would route it just fine -- and then push
# the operator toward an unnecessary mirror.
$gradleWebProxy = $null
if ($gradleProxyHost -and $gradleProxyPort) {
    $proxyUser = if ($gpRaw -match '(?m)^\s*systemProp\.https\.proxyUser\s*=\s*(\S+)') { $Matches[1] } else { $null }
    $proxyPass = if ($gpRaw -match '(?m)^\s*systemProp\.https\.proxyPassword\s*=\s*(\S+)') { $Matches[1] } else { $null }
    $gradleWebProxy = New-Object System.Net.WebProxy("http://${gradleProxyHost}:${gradleProxyPort}", $true)
    if ($proxyUser -and $proxyPass) {
        $gradleWebProxy.Credentials = New-Object System.Net.NetworkCredential($proxyUser, $proxyPass)
    }
    Write-Info "Probes will go through the gradle.properties proxy: ${gradleProxyHost}:${gradleProxyPort}$(if ($proxyUser) { ' (with auth)' })"
}
foreach ($ep in $endpoints) {
    Write-Info "-> $($ep.Url)"
    try {
        $iwrArgs = @{
            Uri = $ep.Url; Method = 'Head'; TimeoutSec = 15
            UseBasicParsing = $true; ErrorAction = 'Stop'
        }
        if ($gradleWebProxy) { $iwrArgs['Proxy'] = $gradleWebProxy.Address.ToString(); $iwrArgs['ProxyCredential'] = $gradleWebProxy.Credentials }
        $r = Invoke-WebRequest @iwrArgs
        if ($r.StatusCode -in 200, 301, 302, 307, 403) {
            Write-Pass "$($ep.Name) reachable (HTTP $($r.StatusCode)) at $($ep.Url)"
            if ($ep.Name -like 'Maven Central*' -and $gradleWebProxy) {
                $mavenCentralReachableViaProxy = $true
            }
        } else {
            Write-Warn "$($ep.Name) returned unexpected HTTP $($r.StatusCode) at $($ep.Url)"
        }
    } catch [System.Net.WebException] {
        $msg = $_.Exception.Message
        $hint = ''
        if ($msg -match 'trust relationship|SSL|certificate|TLS') {
            $hint = "TLS handshake failed -> corporate proxy is intercepting HTTPS. Import the corporate root CA into the JDK truststore (prompt will follow after TLS chain check)."
        } elseif ($msg -match 'proxy|407|authentication') {
            $hint = "Proxy auth failed. Set systemProp.https.proxyUser / proxyPassword in $gradleProps."
        } elseif ($msg -match 'timed out|timeout|unreachable|name resolution|resolved') {
            $hint = "Network unreachable. Check VPN, proxy host/port, and NO_PROXY exclusions."
        }
        Write-Fail "$($ep.Name) unreachable: $($ep.Url) -- $msg" $hint
        if ($ep.Name -like 'Maven Central*') { $mavenCentralReachable = $false }
        if ($ep.Name -like 'Foojay*') {
            Write-Info "  -> The Foojay Disco API is what Gradle calls to AUTO-DOWNLOAD a JDK 17"
            Write-Info "     when your default JDK is a different major version. It's wired into"
            Write-Info "     every settings.gradle.kts via foojay-resolver-convention."
            Write-Info "  -> If Foojay is blocked but your default JDK is already 17-25, turn OFF"
            Write-Info "     auto-download in $gradleProps :"
            Write-Info "       org.gradle.java.installations.auto-download=false"
            Write-Info "       org.gradle.java.installations.auto-detect=true"
            Write-Info "     Gradle then reuses the locally-installed JDK via path discovery."
            Write-Info "  -> Alternatively, pin the discovery path explicitly:"
            Write-Info "       org.gradle.java.installations.paths=C\\:\\Program Files\\Java\\jdk-17"
        }
    } catch {
        Write-Fail "$($ep.Name) unreachable: $($ep.Url) -- $($_.Exception.Message)"
        if ($ep.Name -like 'Maven Central*') { $mavenCentralReachable = $false }
    }
}

# If Maven Central succeeded through the gradle.properties proxy, the
# operator does NOT need a mirror -- proxy alone is sufficient (Pattern A).
# Skip the mirror remediation entirely and tell them so explicitly.
if ($mavenCentralReachableViaProxy) {
    Write-Pass "Maven Central is reachable through your gradle.properties proxy."
    Write-Info "You do NOT need a corporate mirror (no init script, no mavenCentral() rewrite)."
    Write-Info "Pattern A applies: proxy + auth in $gradleProps is sufficient for Gradle to reach"
    Write-Info "the public Maven Central at build time. The 'Corporate repository configuration'"
    Write-Info "section above only matters if you also publish to or resolve from internal repos."
}

# --- Maven Central mirror remediation ---------------------------------
# Only fires if Maven Central is unreachable BOTH directly AND through
# the gradle.properties proxy. In that case the corporate network is
# Pattern B (egress blocked; mirror required) and we offer to wire up
# the Gradle init script.
if (-not $mavenCentralReachable) {
    Write-Host ""
    Write-Host "  Maven Central is unreachable. It IS required for this build (Spring Boot," -ForegroundColor Yellow
    Write-Host "  Kotlin stdlib, JUnit, Jackson, all come from there) -- but in a corporate" -ForegroundColor Yellow
    Write-Host "  network you typically proxy it through an internal Artifactory / Nexus." -ForegroundColor Yellow

    $initDir = Join-Path $env:USERPROFILE '.gradle\init.d'
    $initScript = Join-Path $initDir 'corp-repos.gradle.kts'
    if (Test-Path $initScript) {
        Write-Pass "Found existing init script at $initScript -- Gradle should use the corp mirror from there."
        Write-Info "If resolution still fails, open that file and verify the URL is correct and the credentials are valid."
    } elseif (-not $NonInteractive) {
        # $mavenHints was populated by the "Maven repository configuration"
        # section above; reuse the findings so we don't re-scan.
        Write-Host ""
        Write-Host "  Corporate Maven configuration help:" -ForegroundColor Cyan
        Write-Host "    IT teams often distribute the internal mirror URL via either:"
        Write-Host "      * ~/.m2/settings.xml                         (standard Maven)"
        Write-Host "      * a maven-wrapper.properties file            (Maven wrapper)"
        Write-Host "    If you don't have one, get it from Artifactory itself:"
        Write-Host "      1. Log in to your corporate Artifactory web UI."
        Write-Host "      2. Click the avatar icon in the upper-right corner."
        Write-Host "      3. Select 'Set Me Up'."
        Write-Host "      4. Select 'Maven', then follow the on-screen workflow."
        Write-Host "         Artifactory displays the repo URL and, if needed, credentials."
        Write-Host "         (Gradle honors the same Artifactory Maven repo -- no need to"
        Write-Host "          pick a Gradle-specific one.)"
        Write-Host ""

        if ($mavenHints.Count -gt 0) {
            Write-Host "  Detected existing corporate Maven config on this machine:" -ForegroundColor Yellow
            for ($i = 0; $i -lt $mavenHints.Count; $i++) {
                $h = $mavenHints[$i]
                Write-Host ("    [{0}] {1}" -f ($i + 1), $h.Url)
                Write-Host ("        ({0}, {1})" -f $h.Source, $h.Note) -ForegroundColor DarkGray
            }
            Write-Host "    [m] Enter a different URL manually"
            Write-Host "    [k] Keep existing config as-is  (don't overwrite what's already in ~/.gradle/init.d/ or gradle.properties)"
            Write-Host "    [s] Skip"
            $pick = Read-Host "  Choose"
            $corpUrl = $null
            $corpCreds = $null   # @{Username; Password} if the chosen hint had creds attached
            if ($pick -match '^\d+$' -and [int]$pick -ge 1 -and [int]$pick -le $mavenHints.Count) {
                $chosen = $mavenHints[[int]$pick - 1]
                $corpUrl = $chosen.Url
                $ck = "$($chosen.Source)||$($chosen.RepoId)"
                if ($script:serverCreds.ContainsKey($ck)) {
                    $corpCreds = $script:serverCreds[$ck]
                    Write-Info "Using $corpUrl  (creds from <server id='$($chosen.RepoId)'> in $($chosen.Source))"
                } else {
                    Write-Info "Using $corpUrl  (no matching <server> block; credentials may still be required)"
                }
            } elseif ($pick -match '^m') {
                $corpUrl = Read-Host "  Paste your corporate Maven mirror URL"
            } elseif ($pick -match '^k') {
                Write-Info "Left existing config untouched. Neither ~/.gradle/init.d/corp-repos.gradle.kts nor $gradleProps was modified."
                Write-Info "If Maven Central remains unreachable at build time, re-run this script and pick a candidate URL."
            }
        } else {
            Write-Host "  Your organization's Maven mirror URL usually looks like one of:"
            Write-Host "    https://artifactory.<corp>.com/artifactory/maven-central/"
            Write-Host "    https://nexus.<corp>.com/repository/maven-central/"
            Write-Host "    https://artifacts.<corp>.com/maven-public/"
            $corpUrl = Read-Host "  Paste your corporate Maven mirror URL (or leave blank to skip)"
            $corpCreds = $null
        }
        if ($corpUrl) {
            $corpUrl = $corpUrl.Trim().TrimEnd('/') + '/'
            $null = New-Item -ItemType Directory -Force -Path $initDir

            # If the chosen hint had <server> creds attached, offer to
            # write them to ~/.gradle/gradle.properties so the init
            # script can reference them via providers.gradleProperty().
            # The init script itself never contains the credentials
            # verbatim -- only property-name references.
            $credsApplied = $false
            if ($corpCreds -and $corpCreds.Username) {
                $encrypted = $corpCreds.Password -match '^\{.*\}$'
                if ($encrypted) {
                    Write-Warn "Creds from settings.xml have a Maven-encrypted password." `
                        "Decrypt with 'mvn -ep' or supply settings-security.xml; then re-run this script."
                } else {
                    Write-Host ""
                    Write-Host "  The settings.xml has <server> credentials attached to this URL." -ForegroundColor Yellow
                    Write-Host "  With confirmation, the script will:"
                    Write-Host "    1. Back up $gradleProps"
                    Write-Host "    2. Write two lines:"
                    Write-Host "         orgInternalMavenUser=$($corpCreds.Username)"
                    Write-Host "         orgInternalMavenPassword=<redacted>"
                    Write-Host "    3. Emit an init script that references those properties."
                    $credResp = Read-Host "  Write the credentials to $gradleProps ? [y/N]"
                    if ($credResp -match '^(y|yes)$') {
                        if (Test-Path $gradleProps) {
                            $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
                            $backup = "$gradleProps.backup-$stamp"
                            Copy-Item -Path $gradleProps -Destination $backup -Force
                            Write-Pass "Backed up $gradleProps to $backup"
                        }
                        $existing = if (Test-Path $gradleProps) { Get-Content $gradleProps -Raw } else { '' }
                        # Drop any prior credential lines (all three name-pairs) so we don't double-up.
                        $cleaned = ($existing `
                            -replace '(?m)^\s*orgInternalMaven(User|Password)\s*=.*\r?\n?', '' `
                            -replace '(?m)^\s*systemProp\.gradle\.wrapper(User|Password)\s*=.*\r?\n?', '' `
                            -replace '(?m)^\s*artifactoryResolver(Username|Password)\s*=.*\r?\n?', '')
                        # Write three credential-pair aliases, all sharing the same
                        # username/password. Different Gradle code paths read different
                        # names:
                        #   orgInternalMavenUser/Password           -> our init script
                        #   systemProp.gradle.wrapperUser/Password  -> Gradle wrapper ZIP download
                        #   artifactoryResolverUsername/Password    -> direct Artifactory resolver blocks
                        $credBlock = @"

# Added by build-health-check.ps1 on $(Get-Date -Format 's').
# Credentials sourced from <server id='$($chosen.RepoId)'> in
# $($chosen.Source). DO NOT commit gradle.properties to any repo.
# All three pairs below carry the same username/password -- different
# Gradle code paths look at different names.
orgInternalMavenUser=$($corpCreds.Username)
orgInternalMavenPassword=$($corpCreds.Password)
systemProp.gradle.wrapperUser=$($corpCreds.Username)
systemProp.gradle.wrapperPassword=$($corpCreds.Password)
artifactoryResolverUsername=$($corpCreds.Username)
artifactoryResolverPassword=$($corpCreds.Password)
"@
                        [System.IO.File]::WriteAllText($gradleProps, $cleaned + $credBlock, (New-Object System.Text.UTF8Encoding($false)))
                        Write-Pass "Wrote orgInternalMavenUser / orgInternalMavenPassword to $gradleProps"
                        $credsApplied = $true
                    } else {
                        Write-Info "Skipped credential write; init script will still reference the property names."
                    }
                }
            }

            # Figure out whether Basic or Bearer auth was accepted
            # during the candidate-probe phase. We ran every hint
            # through Probe-MirrorUrl; the chosen one's result is the
            # authoritative signal for which header to emit.
            $needBearer = $false
            if ($corpCreds) {
                $reprobe = Probe-MirrorUrl $corpUrl $corpCreds.Username $corpCreds.Password
                if ($reprobe.Status -eq 'reachable' -and $reprobe.AuthMode -eq 'basic+bearer-retry') {
                    $needBearer = $true
                    Write-Info "Probe confirmed Artifactory wants Bearer; init script will send the token via Authorization header."
                }
            }

            # Build the init script. Gradle's credentials{} block only
            # does HTTP Basic, so when we need Bearer we use a
            # HttpHeaderCredentials / authentication{} stanza instead.
            # Because the init script now rewrites inside a shared
            # function with `repo` as a named parameter, the credentials
            # calls must be qualified on `repo`.
            $credsClause = if ($credsApplied -and -not $needBearer) { @'

            repo.credentials {
                username = providers.gradleProperty("orgInternalMavenUser").get()
                password = providers.gradleProperty("orgInternalMavenPassword").get()
            }
'@ } elseif ($credsApplied -and $needBearer) { @'

            repo.credentials(HttpHeaderCredentials::class) {
                name = "Authorization"
                value = "Bearer " + providers.gradleProperty("orgInternalMavenPassword").get()
            }
            repo.authentication {
                create<HttpHeaderAuthentication>("header")
            }
'@ } else { '' }
            $content = @"
// Added by build-health-check.ps1 on $(Get-Date -Format 's').
// Redirects mavenCentral() AND gradlePluginPortal() to the corporate
// proxy across every resolution scope Gradle uses:
//   - settings.pluginManagement.repositories   (plugin resolution)
//   - settings.dependencyResolutionManagement  (centralized repos)
//   - allprojects.buildscript.repositories     (per-project plugins)
//   - allprojects.repositories                 (project dependencies)
// Gradle 9.x tightened plugin resolution to use settings-time repos
// only; without the settingsEvaluated hook below, plugins declared
// via  plugins { id("...") version "..." }  would miss the rewrite
// and hit repo.maven.apache.org directly.
// To revert: delete this file.
val corpMavenUrl = "$corpUrl"

fun maybeRewrite(repo: ArtifactRepository) {
    if (repo is MavenArtifactRepository) {
        val u = repo.url.toString()
        if (u.contains("repo.maven.apache.org") ||
            u.contains("plugins.gradle.org") ||
            u.contains("repo1.maven.org")) {
            repo.setUrl(corpMavenUrl)$credsClause
        }
    }
}

settingsEvaluated {
    pluginManagement.repositories.configureEach { maybeRewrite(this) }
    dependencyResolutionManagement.repositories.configureEach { maybeRewrite(this) }
}

allprojects {
    buildscript.repositories.configureEach { maybeRewrite(this) }
    repositories.configureEach { maybeRewrite(this) }
}
"@
            [System.IO.File]::WriteAllText($initScript, $content, (New-Object System.Text.UTF8Encoding($false)))
            Write-Pass "Wrote $initScript  ->  rewrites mavenCentral() to $corpUrl"
            if ($credsApplied) {
                Write-Pass "Init script references orgInternalMavenUser / orgInternalMavenPassword via gradle.properties."
            }
            Write-Info "This applies to EVERY Gradle build on this user account. Delete the file to revert."

            # Post-write verification -- use the same creds we just
            # wired up. Probe-MirrorUrl will do its own Basic -> Bearer
            # cascade, so we just report whichever mode succeeded.
            Write-Host ""
            Write-Info "Verifying Gradle can resolve artifacts via the new mirror..."
            $probeArtifact = $corpUrl.TrimEnd('/') + '/org/springframework/boot/spring-boot/maven-metadata.xml'
            Write-Info "-> $probeArtifact"
            $verify = if ($corpCreds -and ($corpCreds.Username -or $corpCreds.Password)) {
                Probe-MirrorUrl $probeArtifact $corpCreds.Username $corpCreds.Password
            } else {
                Probe-MirrorUrl $probeArtifact
            }
            switch ($verify.Status) {
                'reachable' {
                    Write-Pass "Mirror resolves Spring Boot metadata OK  ($($verify.Message)). Settings.xml has been applied; Gradle should now succeed."
                    if ($verify.AuthMode -eq 'basic+bearer-retry') {
                        if ($needBearer) {
                            Write-Pass "Init script is already configured for Bearer auth ($($verify.Message))."
                        } else {
                            Write-Warn "Verification only worked with Bearer, but the init script uses Basic credentials{}." `
                                "Re-run the remediation prompt -- it will now detect the Bearer requirement and emit the HttpHeaderAuthentication form."
                        }
                    }
                }
                'unauth' {
                    if ($credsApplied) {
                        Write-Fail "Mirror returned $($verify.Message) even with the <server> creds from settings.xml." `
                            "Token/password may be wrong or expired. Re-run the Artifactory 'Set Me Up -> Maven' flow and replace the password in settings.xml."
                        if ($verify.WwwAuth) { Write-Info "    server demands: WWW-Authenticate: $($verify.WwwAuth)" }
                        if ($null -ne $verify.BearerRetryCode) {
                            Write-Info "    Bearer retry during verification: HTTP $($verify.BearerRetryCode)"
                        }
                    } else {
                        Write-Warn "Mirror returned $($verify.Message)." `
                            "No <server> creds were attached. Paste a user/token into orgInternalMavenUser / orgInternalMavenPassword in $gradleProps."
                    }
                }
                'tls' {
                    Write-Warn "Mirror reachable but TLS chain rejected ($($verify.Message))." `
                        "The TLS chain check further down will offer to import the corporate root."
                }
                default {
                    Write-Fail "Mirror probe FAILED ($($verify.Message))" `
                        "URL may be wrong or unreachable from this host. Check VPN and proxy config."
                }
            }
        } else {
            Write-Info "Skipped. You can hand-craft ~/.gradle/init.d/corp-repos.gradle.kts later."
        }
    }
}

# ---------------------------------------------------------------------------
# TLS / corporate CA detection
# ---------------------------------------------------------------------------
Write-Section "TLS chain check"

# Use a low-level TcpClient + SslStream so we can inspect the cert without
# Invoke-WebRequest hiding the chain behind its own validation.
$tlsHost = 'services.gradle.org'
Write-Info "-> https://${tlsHost}:443 (TLS chain inspection)"
$rootCert = $null
try {
    $tcp = New-Object System.Net.Sockets.TcpClient($tlsHost, 443)
    $tcp.ReceiveTimeout = 10000
    $tcp.SendTimeout = 10000
    $stream = $tcp.GetStream()
    $ssl = New-Object System.Net.Security.SslStream($stream, $false, ({ param($s,$cert,$chain,$err) $true }))
    $ssl.AuthenticateAsClient($tlsHost)
    $cert = New-Object System.Security.Cryptography.X509Certificates.X509Certificate2($ssl.RemoteCertificate)
    $chain = New-Object System.Security.Cryptography.X509Certificates.X509Chain
    $chain.ChainPolicy.RevocationMode = 'NoCheck'
    [void]$chain.Build($cert)
    $rootCert = $chain.ChainElements[$chain.ChainElements.Count - 1].Certificate
    $rootCN = $rootCert.Subject
    if ($rootCN -match 'CN=USERTrust|CN=DigiCert|CN=ISRG|CN=Baltimore|CN=GlobalSign|CN=Amazon|CN=GTS|CN=Microsoft') {
        Write-Pass "TLS chain to $tlsHost terminates at a public root: $rootCN"
        $rootCert = $null  # don't offer to import a public root
    } else {
        Write-Warn "TLS chain to $tlsHost terminates at NON-public root: $rootCN" `
            "Likely corporate TLS interception. Gradle (and any JVM HTTP client) will reject this chain unless the root is in the JDK truststore."
    }
    $ssl.Dispose(); $tcp.Dispose()
} catch {
    Write-Warn "Could not perform TLS chain check on $tlsHost`: $($_.Exception.Message)"
}

# If we captured a corporate root, offer remediation -- but first check
# whether the operator has already applied one of our workarounds, so
# we don't nag them on every run.
if ($rootCert) {
    # Check 1: is the cert already in the JDK truststore?
    $alreadyInJdk = $false
    if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\lib\security\cacerts")) {
        $cacerts = "$env:JAVA_HOME\lib\security\cacerts"
        # Fingerprint match is cheaper than keytool -list | find. Pull the
        # SHA-1 of every trusted cert and see if the corporate root's
        # SHA-1 is among them.
        $rootSha1 = $rootCert.GetCertHashString('SHA1')
        $kl = & keytool -list -keystore $cacerts -storepass changeit 2>$null
        if ($LASTEXITCODE -eq 0 -and ($kl -match $rootSha1)) {
            $alreadyInJdk = $true
        }
    }

    # Check 2: does gradle.properties already carry a trustStore override?
    $alreadyInGradle = $false
    if (Test-Path $gradleProps) {
        $gpRaw = Get-Content $gradleProps -Raw
        if ($gpRaw -match '(?m)^\s*systemProp\.javax\.net\.ssl\.trustStore\s*=') {
            $alreadyInGradle = $true
        }
    }

    if ($alreadyInJdk) {
        Write-Pass "Corporate root is already present in the JDK truststore ($cacerts). No action needed."
    } elseif ($alreadyInGradle) {
        Write-Pass "Gradle is already configured to use an alternate truststore via $gradleProps (systemProp.javax.net.ssl.trustStore)."
        Write-Info "The raw socket probe above still sees the corporate root -- that is expected; Gradle respects the override you already wrote."
        Write-Info "To revert, restore $gradleProps from its .backup-* sibling (or delete the systemProp.javax.net.ssl.trustStore line)."
    } elseif (-not $NonInteractive) {
        Write-Host ""
        Write-Host "  Corporate root detected. Three ways forward:" -ForegroundColor Yellow
        Write-Host "    [1] Import the cert into the JDK truststore  (recommended, permanent fix)"
        Write-Host "    [2] Point Gradle at the Windows system truststore (SunMSCAPI) via gradle.properties"
        Write-Host "        -- most corp networks have the root already trusted there via GPO, so this just wires it in"
        Write-Host "    [s] Skip"
        $resp = Read-Host "  Choose [1/2/s]"
        switch -regex ($resp) {
            '^1' {
                if (-not $env:JAVA_HOME -or -not (Test-Path "$env:JAVA_HOME\lib\security\cacerts")) {
                    Write-Fail "JAVA_HOME is not set or cacerts not found; cannot import." `
                        "Set JAVA_HOME to a JDK 17+ install and re-run."
                } else {
                    $cacerts = "$env:JAVA_HOME\lib\security\cacerts"
                    $tmp = [System.IO.Path]::GetTempFileName() + '.cer'
                    [System.IO.File]::WriteAllBytes($tmp, $rootCert.Export('Cert'))
                    $alias = "corp-root-$(Get-Date -Format yyyyMMddHHmm)"
                    Write-Host "  Importing into $cacerts as alias $alias (password: changeit)" -ForegroundColor Cyan
                    & keytool -importcert -noprompt -alias $alias -file $tmp -keystore $cacerts -storepass changeit
                    if ($LASTEXITCODE -eq 0) {
                        Write-Pass "Corporate root imported into JDK truststore. Retry your build."
                    } else {
                        Write-Fail "keytool returned exit $LASTEXITCODE. Run with an elevated shell if permission denied."
                    }
                    Remove-Item $tmp -Force -ErrorAction SilentlyContinue
                }
            }
            '^2' {
                Write-Host ""
                Write-Host "  This will add the following to $gradleProps :" -ForegroundColor Yellow
                Write-Host "    systemProp.javax.net.ssl.trustStore=NUL"
                Write-Host "    systemProp.javax.net.ssl.trustStoreType=Windows-ROOT"
                Write-Host ""
                Write-Host "  Effect: Gradle (and any JVM it launches) uses the Windows certificate"
                Write-Host "  store instead of the JDK's bundled cacerts. The corporate root is typically"
                Write-Host "  installed there already by your org's MDM / GPO, which is why this works"
                Write-Host "  without needing keytool or admin rights. It does NOT disable TLS verification"
                Write-Host "  -- validation still happens, just against the Windows-managed trust store."
                Write-Host "  Existing $gradleProps will be backed up before the edit." -ForegroundColor Yellow
                $confirm = Read-Host "  Proceed? [y/N]"
                if ($confirm -match '^(y|yes)$') {
                    $null = New-Item -ItemType Directory -Force -Path (Split-Path $gradleProps)
                    if (Test-Path $gradleProps) {
                        $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
                        $backup = "$gradleProps.backup-$stamp"
                        Copy-Item -Path $gradleProps -Destination $backup -Force
                        Write-Pass "Backed up existing config to $backup"
                    }
                    $existing = if (Test-Path $gradleProps) { Get-Content $gradleProps -Raw } else { '' }
                    # Remove any stale trustStore lines before appending.
                    $cleaned = ($existing -replace '(?m)^\s*systemProp\.javax\.net\.ssl\.trustStore(Type)?\s*=.*\r?\n?', '')
                    $append = @"

# Added by build-health-check.ps1 on $(Get-Date -Format 's') -- route Gradle
# TLS validation through the Windows system trust store (SunMSCAPI), which
# the corporate root is trusted in on most managed machines via GPO/MDM.
# To revert: restore the .backup-* sibling of this file.
systemProp.javax.net.ssl.trustStore=NUL
systemProp.javax.net.ssl.trustStoreType=Windows-ROOT
"@
                    # Use utf8NoBOM so Gradle's java.util.Properties reader
                    # doesn't choke on a BOM at the top of the file.
                    [System.IO.File]::WriteAllText($gradleProps, $cleaned + $append, (New-Object System.Text.UTF8Encoding($false)))
                    Write-Pass "Wrote Windows-truststore override to $gradleProps."
                    Write-Info "Re-run the script to confirm: on next run it will detect the override and NOT prompt again."
                } else {
                    Write-Info "Cancelled -- gradle.properties not modified."
                }
            }
            default { Write-Info "Skipped CA-import remediation." }
        }
    }
}

# ---------------------------------------------------------------------------
# Disk space
# ---------------------------------------------------------------------------
Write-Section "Disk space"

$gradleHome = Join-Path $env:USERPROFILE '.gradle'
$drive = (Get-Item $env:USERPROFILE).PSDrive
$freeGb = [math]::Round($drive.Free / 1GB, 1)
if ($freeGb -lt 5) {
    Write-Fail "$($drive.Name):\ has only $freeGb GB free; Gradle needs at least 5 GB for distribution + caches"
} elseif ($freeGb -lt 10) {
    Write-Warn "$($drive.Name):\ has $freeGb GB free; recommend 10 GB+ for repeated benchmark runs"
} else {
    Write-Pass "$($drive.Name):\ has $freeGb GB free"
}
Write-Info "Gradle user home: $gradleHome"

# ---------------------------------------------------------------------------
# JVM-level HTTP auth + IPv4 preference gotchas
# ---------------------------------------------------------------------------
# Java 8u111+ disables HTTP Basic auth over HTTPS CONNECT tunnels by
# default. Corporate proxies that require Basic/NTLM on CONNECT return
# 407 even with correct creds. Surface the required flags when we see
# a proxyPassword in gradle.properties.
Write-Section "JVM HTTP auth settings"
if (Test-Path $gradleProps) {
    $gpText = Get-Content $gradleProps -Raw
    if ($gpText -match '(?m)^\s*systemProp\.https?\.proxyPassword\s*=') {
        $hasTunneling = ($gpText -match '(?m)^\s*systemProp\.jdk\.http\.auth\.tunneling\.disabledSchemes\s*=')
        $hasProxying  = ($gpText -match '(?m)^\s*systemProp\.jdk\.http\.auth\.proxying\.disabledSchemes\s*=')
        if (-not $hasTunneling -or -not $hasProxying) {
            Write-Warn "Proxy credentials present but Basic-over-CONNECT is disabled by default in JDK 17+." `
                "Corporate proxies that authenticate via Basic / NTLM on the HTTPS CONNECT tunnel will return 407 'Proxy Authentication Required' even with correct creds."
            Write-Info "  To re-enable, add to $gradleProps :"
            Write-Info "    systemProp.jdk.http.auth.tunneling.disabledSchemes="
            Write-Info "    systemProp.jdk.http.auth.proxying.disabledSchemes="
            Write-Info "  (empty value = no schemes disabled = Basic/NTLM re-allowed)"
        } else {
            Write-Pass "jdk.http.auth.tunneling/proxying disabledSchemes already cleared in $gradleProps"
        }
    }
    # IPv4 preference: corporate egress is often IPv4-only while the JVM
    # tries AAAA first, causing 30s connect timeouts. Surface the flag
    # if we don't see it set already.
    if ($gpText -match '(?m)^\s*systemProp\.java\.net\.preferIPv4Stack\s*=\s*true') {
        Write-Pass "systemProp.java.net.preferIPv4Stack=true set in $gradleProps"
    } else {
        Write-Info "systemProp.java.net.preferIPv4Stack not set."
        Write-Info "  If the build hangs on 'Connect timed out' at dependency resolution, add:"
        Write-Info "    systemProp.java.net.preferIPv4Stack=true"
        Write-Info "  to $gradleProps. Forces the JVM to skip AAAA DNS lookups entirely."
    }
}

# ---------------------------------------------------------------------------
# Gradle wrapper distribution (all four sub-project wrappers)
# ---------------------------------------------------------------------------
Write-Section "Gradle wrapper download"

$wrapperErrors   = 0
$wrapperWarnings = 0
foreach ($proj in @('banking-app', 'bench-harness', 'bench-cli', 'bench-webui')) {
    $wrapperProps = Join-Path $script:RepoRoot "$proj\gradle\wrapper\gradle-wrapper.properties"
    $wrapperJar   = Join-Path $script:RepoRoot "$proj\gradle\wrapper\gradle-wrapper.jar"
    if (-not (Test-Path $wrapperProps)) {
        Write-Warn "${proj}: gradle-wrapper.properties not found at $wrapperProps"
        $wrapperErrors++
        continue
    }
    $propsRaw = Get-Content $wrapperProps -Raw
    $distUrl  = if ($propsRaw -match '(?m)^distributionUrl=(.+)') { $Matches[1] -replace '\\:', ':' } else { $null }
    $netTo    = if ($propsRaw -match '(?m)^\s*networkTimeout\s*=\s*(\d+)') { [int]$Matches[1] } else { $null }
    if ($netTo -and $netTo -lt 30000) {
        Write-Warn "${proj}: networkTimeout=$netTo ms is short for corporate networks" `
            "Bump to 60000-120000 in $wrapperProps to avoid spurious failures under proxy latency."
        $wrapperWarnings++
    }

    # Reachability check, through the gradle.properties proxy when set.
    if ($distUrl) {
        try {
            $iwrArgs = @{
                Uri = $distUrl; Method = 'Head'; TimeoutSec = 30
                UseBasicParsing = $true; ErrorAction = 'Stop'
            }
            if ($gradleWebProxy) {
                $iwrArgs['Proxy'] = $gradleWebProxy.Address.ToString()
                $iwrArgs['ProxyCredential'] = $gradleWebProxy.Credentials
            }
            $r = Invoke-WebRequest @iwrArgs
            if ($r.StatusCode -eq 200) {
                $sizeMb = [math]::Round([int64]$r.Headers['Content-Length'] / 1MB, 1)
                Write-Pass "${proj}: distribution reachable: $distUrl ($sizeMb MB)"
            } else {
                Write-Warn "${proj}: distribution HEAD returned HTTP $($r.StatusCode)"
                $wrapperWarnings++
            }
        } catch {
            Write-Fail "${proj}: distribution NOT downloadable: $distUrl" `
                "Without this download .\gradlew.bat in $proj\ will fail before Gradle even starts."
            $wrapperErrors++
        }
    }

    # Local wrapper jar integrity. Corporate MITM proxies have been
    # observed corrupting binary downloads during inspection, producing
    # the classic "Could not find or load main class
    # org.gradle.wrapper.GradleWrapperMain" error.
    if (Test-Path $wrapperJar) {
        $jarBytes = (Get-Item $wrapperJar).Length
        if ($jarBytes -lt 20000) {
            Write-Fail "${proj}: gradle-wrapper.jar is only $jarBytes bytes - looks truncated or corrupted" `
                "Expected ~40-80 KB. Run 'git checkout -- $proj\gradle\wrapper\gradle-wrapper.jar' to restore. Proxy content inspection sometimes strips bytes."
            $wrapperErrors++
        } elseif ($jarBytes -gt 200000) {
            Write-Warn "${proj}: gradle-wrapper.jar is $jarBytes bytes (larger than expected ~43 KB)" `
                "Could indicate the proxy replaced the jar with an HTML inspection page. Open in a hex editor; if it starts with '<!DOCTYPE' or '<html', that's the cause."
            $wrapperWarnings++
        } else {
            # Zip-integrity sanity check via .NET's ZipFile.
            try {
                Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction SilentlyContinue
                $zip = [System.IO.Compression.ZipFile]::OpenRead($wrapperJar)
                $zip.Dispose()
            } catch {
                Write-Fail "${proj}: gradle-wrapper.jar is NOT a valid zip" `
                    "Run 'git checkout -- $proj\gradle\wrapper\gradle-wrapper.jar' to restore it."
                $wrapperErrors++
            }
        }
    } else {
        Write-Fail "${proj}: gradle-wrapper.jar missing at $wrapperJar" `
            "Run 'git checkout -- $proj\gradle\wrapper\gradle-wrapper.jar' to restore it."
        $wrapperErrors++
    }
}
if ($wrapperErrors -eq 0 -and $wrapperWarnings -eq 0) {
    Write-Pass "All four sub-project wrappers look healthy."
}

# ---------------------------------------------------------------------------
# End-to-end: gradlew --version
#
# The ultimate smoke test. If every diagnostic above passed but this
# still fails, the problem is in the wrapper bootstrap path
# (distribution download, SHA-256 verification, or JVM startup) -- and
# the output of this run is the same error the user would see running
# .\gradlew.bat build directly.
# ---------------------------------------------------------------------------
Write-Section "End-to-end: gradlew --version"
if ($wrapperErrors -gt 0) {
    Write-Info "Skipping end-to-end probe - fix the wrapper errors above first."
} else {
    $wrapperBat = Join-Path $script:RepoRoot 'banking-app\gradlew.bat'
    if (-not (Test-Path $wrapperBat)) {
        Write-Warn "banking-app\gradlew.bat missing or not executable; skipping."
    } else {
        Write-Info "Running `"$wrapperBat --version`" (may download ~150 MB Gradle distribution on first run)..."
        $tmplog = [System.IO.Path]::GetTempFileName()
        try {
            $p = Start-Process -FilePath $wrapperBat `
                -ArgumentList '-p', (Join-Path $script:RepoRoot 'banking-app'), '--version' `
                -WorkingDirectory (Join-Path $script:RepoRoot 'banking-app') `
                -NoNewWindow -Wait -PassThru `
                -RedirectStandardOutput $tmplog `
                -RedirectStandardError "$tmplog.err"
            $logText = (Get-Content $tmplog -Raw -ErrorAction SilentlyContinue) + "`n" + (Get-Content "$tmplog.err" -Raw -ErrorAction SilentlyContinue)
            if ($p.ExitCode -eq 0 -and $logText -match '(?m)^Gradle\s+\d') {
                $gradleVer = ([regex]::Match($logText, '(?m)^Gradle\s+\S+').Value)
                $jvmVer    = ([regex]::Match($logText, '(?m)^JVM:\s+.*').Value)
                Write-Pass "Wrapper bootstrapped cleanly - $gradleVer"
                if ($jvmVer) { Write-Info "  $jvmVer" }
            } else {
                Write-Fail "Wrapper bootstrap FAILED - this is the same error you see when .\gradlew.bat build runs." `
                    "Inspect $tmplog for the exact cause. Most common: distribution SHA-256 mismatch (MITM proxy), wrapper jar corrupt, or JDK incompatibility."
                Write-Info "  Last 15 lines of the wrapper output:"
                $logText -split "`n" | Select-Object -Last 15 | ForEach-Object { Write-Info "    $_" }
                if ($logText -match 'Could not find or load main class org\.gradle\.wrapper\.GradleWrapperMain') {
                    Write-Info "  -> gradle-wrapper.jar is present but unreadable by the JVM. Either the file is"
                    Write-Info "     truncated (size check above would catch), or JVM classpath resolution is broken."
                    Write-Info "     Try: del $wrapperJar; git checkout -- banking-app\gradle\wrapper\gradle-wrapper.jar"
                } elseif ($logText -match 'Verification of Gradle distribution failed') {
                    Write-Info "  -> The downloaded Gradle zip does not match the SHA-256 in gradle-wrapper.properties."
                    Write-Info "     Corporate MITM proxy is rewriting the zip during inspection. Two fixes:"
                    Write-Info "       (a) Import corp CA so TLS is not intercepted, OR"
                    Write-Info "       (b) Download the zip manually through a browser, verify SHA-256,"
                    Write-Info "           and place in %GRADLE_USER_HOME%\wrapper\dists\gradle-9.4.1-bin\<hash>\"
                } elseif ($logText -match 'PKIX|SSLHandshake|unable to find valid certification path') {
                    Write-Info "  -> JDK truststore missing the corporate root CA. See TLS chain section above."
                } elseif ($logText -match '407 Proxy Authentication Required') {
                    Write-Info "  -> Proxy CONNECT auth rejected. See JVM HTTP auth section above."
                }
            }
        } finally {
            Remove-Item $tmplog -Force -ErrorAction SilentlyContinue
            Remove-Item "$tmplog.err" -Force -ErrorAction SilentlyContinue
        }
    }
}

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
Write-Host ""
Write-Host "================================================================" -ForegroundColor White
if ($script:Failures.Count -eq 0) {
    Write-Host "  Build environment looks healthy." -ForegroundColor Green
    if ($script:Warnings.Count -gt 0) {
        Write-Host "  $($script:Warnings.Count) warning(s) above are advisory." -ForegroundColor Yellow
    }
    exit 0
} else {
    Write-Host "  $($script:Failures.Count) BLOCKER(s) found. Address them before running .\gradlew.bat build:" -ForegroundColor Red
    foreach ($f in $script:Failures) { Write-Host "    * $f" -ForegroundColor Red }
    exit 1
}
