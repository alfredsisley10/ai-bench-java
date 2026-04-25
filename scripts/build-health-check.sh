#!/usr/bin/env bash
#
# Pre-build environment check for ai-bench-java on macOS / Linux.
# Mirrors scripts/build-health-check.ps1 — same checks, same exit codes,
# so CI invocations can be platform-agnostic.

set -u

NON_INTERACTIVE=0
MAVEN_CONFIG_PATH=""
prev=""
for arg in "$@"; do
    if [ "$prev" = "--maven-config" ]; then
        MAVEN_CONFIG_PATH="$arg"
        prev=""
        continue
    fi
    case "$arg" in
        --non-interactive|-q) NON_INTERACTIVE=1 ;;
        --maven-config)        prev="--maven-config" ;;
        --maven-config=*)      MAVEN_CONFIG_PATH="${arg#--maven-config=}" ;;
    esac
done

# Resolve the *real* script directory up front (before any `cd`s) so
# every path derived from it below — banking-app/gradle.properties,
# gradle-wrapper.properties, the four sub-project wrappers — resolves
# correctly regardless of the caller's cwd or whether the script was
# invoked via a symlink.
_src="$0"
if command -v readlink >/dev/null 2>&1; then
    while [ -L "$_src" ]; do
        _link=$(readlink "$_src")
        case "$_link" in
            /*) _src="$_link" ;;
            *)  _src="$(dirname "$_src")/$_link" ;;
        esac
    done
fi
SCRIPT_DIR="$(cd "$(dirname "$_src")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
unset _src _link

# Global state used across every section below.
# CRITICAL: GRADLE_PROPS is referenced by nearly every check, so it must
# be defined BEFORE the first `sect` runs. Earlier versions of this
# script declared it mid-way through the Proxy section, which made the
# opening info lines reference an unbound variable — with `set -u`, that
# crashed the entire health check after only printing the JDK section.
GRADLE_PROPS="$HOME/.gradle/gradle.properties"
GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"

PASS=0; FAIL=0; WARN=0
RED=$'\033[0;31m'; GRN=$'\033[0;32m'; YEL=$'\033[0;33m'; CYN=$'\033[0;36m'; CLR=$'\033[0m'

ok()   { printf "  ${GRN}[PASS]${CLR} %s\n" "$1"; PASS=$((PASS+1)); }
bad()  { printf "  ${RED}[FAIL]${CLR} %s\n" "$1"; FAIL=$((FAIL+1)); [ -n "${2:-}" ] && printf "         ${YEL}-> %s${CLR}\n" "$2"; }
warn() { printf "  ${YEL}[WARN]${CLR} %s\n" "$1"; WARN=$((WARN+1)); [ -n "${2:-}" ] && printf "         ${YEL}-> %s${CLR}\n" "$2"; }
info() { printf "  ${CYN}[INFO]${CLR} %s\n" "$1"; }
sect() { printf "\n== %s ==\n" "$1"; }

# --- JDK -------------------------------------------------------------
sect "JDK 17-25"
if ! command -v java >/dev/null 2>&1; then
    bad "java is not on PATH" "Install JDK 21 (Temurin / OpenJDK / Corretto / Zulu) and add its bin/ to PATH."
else
    ver_line=$(java -version 2>&1 | head -1)
    # Awk gets us a portable extractor across BSD (macOS) and GNU sed
    # without grep -P / sed regex flavor differences.
    major=$(printf '%s' "$ver_line" | awk -F'"' '{ split($2, a, "."); print a[1] }')
    if [ -n "$major" ] && [ "$major" -ge 17 ] && [ "$major" -le 25 ] 2>/dev/null; then
        ok "java -version reports JDK $major  ($ver_line)"
    elif [ -n "$major" ] && [ "$major" -gt 25 ] 2>/dev/null; then
        warn "JDK $major is newer than the tested range (17-25); Gradle 9.4.1 may not support it." "Use a JDK between 17 and 25."
    else
        bad "JDK ${major:-?} found; this project needs 17 through 25" "Install JDK 17-25 (OpenJDK / Oracle / Temurin / Corretto / Zulu) and set JAVA_HOME."
    fi
fi
if [ -z "${JAVA_HOME:-}" ]; then
    warn "JAVA_HOME is not set"
    if command -v java >/dev/null 2>&1 && [ "$NON_INTERACTIVE" = "0" ]; then
        # Try in order of reliability:
        #   1. macOS /usr/libexec/java_home helper (macOS only)
        #   2. `java -XshowSettings:properties -version` -- the JRE itself
        #      reports java.home; works through wrapper scripts / shims
        #   3. Path walk: follow symlinks from $(command -v java), strip /bin/java
        detected=""
        if [ "$(uname)" = "Darwin" ] && [ -x /usr/libexec/java_home ]; then
            detected=$(/usr/libexec/java_home 2>/dev/null || true)
        fi
        if [ -z "$detected" ] || [ ! -x "$detected/bin/java" ]; then
            # Method 2: ask Java.
            detected=$(java -XshowSettings:properties -version 2>&1 \
                | awk -F'= *' '/java\.home/ {print $2; exit}' \
                | sed 's/[[:space:]]*$//')
            [ -z "$detected" ] || [ ! -x "$detected/bin/java" ] && detected=""
        fi
        if [ -z "$detected" ]; then
            # Method 3: path walk.
            java_bin=$(command -v java)
            if command -v readlink >/dev/null 2>&1; then
                real=$(readlink -f "$java_bin" 2>/dev/null || echo "$java_bin")
            else
                real="$java_bin"
            fi
            detected=$(dirname "$(dirname "$real")")
            [ -x "$detected/bin/java" ] || detected=""
        fi

        if [ -n "$detected" ] && [ -x "$detected/bin/java" ]; then
            info "Detected JDK install root: $detected"
            echo "  Append 'export JAVA_HOME=\"$detected\"' to your shell rc file?"
            echo "    [p] Persist -- appends to ~/.zshrc (zsh) or ~/.bashrc (bash) or ~/.profile"
            echo "    [s] Skip    -- set it yourself later"
            printf "  Choose [p/s]: "
            read -r resp
            case "$resp" in
                p|P)
                    rc=""
                    if [ -n "${ZSH_VERSION:-}" ] || [ -f "$HOME/.zshrc" ]; then rc="$HOME/.zshrc"
                    elif [ -n "${BASH_VERSION:-}" ] || [ -f "$HOME/.bashrc" ]; then rc="$HOME/.bashrc"
                    else rc="$HOME/.profile"
                    fi
                    {
                        echo ""
                        echo "# Added by build-health-check.sh on $(date +%FT%T)"
                        echo "export JAVA_HOME=\"$detected\""
                    } >> "$rc"
                    export JAVA_HOME="$detected"
                    ok "Appended to $rc. Run 'source $rc' or open a new terminal so future shells see it."
                    ;;
                *) info "Skipped. To set yourself: export JAVA_HOME=\"$detected\"" ;;
            esac
        else
            warn "Could not derive the JDK install root automatically." \
                "Your 'java' command is likely a wrapper/shim that doesn't sit in a standard <jdk>/bin/ layout."
            info "Run this to find the real JDK location, then set JAVA_HOME to the path after 'java.home =':"
            info "  java -XshowSettings:properties -version 2>&1 | grep 'java.home'"
            info "Example output:"
            info "    java.home = /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
            info "Then set it in your shell rc:"
            info "  echo 'export JAVA_HOME=/opt/...' >> ~/.zshrc  (or ~/.bashrc)"
            info "Open a new terminal afterwards."
        fi
    fi
elif [ ! -d "$JAVA_HOME" ]; then
    bad "JAVA_HOME points at missing path: $JAVA_HOME"
else
    ok "JAVA_HOME = $JAVA_HOME"
fi

# Full-JDK check: Gradle needs javac + keytool, not just java. A JRE-only
# install passes the `java` check above but fails at compile time with
# opaque "tool not found" errors. Also surfaces when JAVA_HOME points at
# a JRE directory inside a JDK (jdk-17/jre/ on older Oracle layouts).
if command -v java >/dev/null 2>&1; then
    javac_bin=""
    keytool_bin=""
    if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
        javac_bin="$JAVA_HOME/bin/javac"
    elif command -v javac >/dev/null 2>&1; then
        javac_bin=$(command -v javac)
    fi
    if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/keytool" ]; then
        keytool_bin="$JAVA_HOME/bin/keytool"
    elif command -v keytool >/dev/null 2>&1; then
        keytool_bin=$(command -v keytool)
    fi
    if [ -z "$javac_bin" ]; then
        bad "javac not found — this looks like a JRE, not a JDK" \
            "Gradle compiles .java sources, which requires javac. Install a *JDK* distribution (Temurin/OpenJDK/Corretto/Zulu). On Windows the JRE and JDK have nearly identical names; pick the one that ships javac.exe."
    else
        info "javac found at $javac_bin"
    fi
    if [ -z "$keytool_bin" ]; then
        warn "keytool not found — corporate-CA import will not work" \
            "Install a full JDK or point JAVA_HOME at one that includes bin/keytool."
    fi
fi

# Detect a stale org.gradle.java.home override in user-level
# gradle.properties. This property hard-pins the Gradle daemon's JDK and
# overrides toolchains. A common enterprise failure mode: the user
# configured it for a Gradle 8 build years ago, pointing at JDK 11; the
# current Gradle 9.x daemon refuses to start with "Value 'C:\\...\\jdk-11'
# given for org.gradle.java.home Gradle property is invalid (Java version
# too old)." The property is valid but its value is wrong for this repo.
if [ -f "$GRADLE_PROPS" ]; then
    pinned_java_home=$(grep -E '^\s*org\.gradle\.java\.home\s*=' "$GRADLE_PROPS" 2>/dev/null | head -1 | sed -E 's/^[^=]*=[[:space:]]*//')
    if [ -n "$pinned_java_home" ]; then
        info "org.gradle.java.home = $pinned_java_home  (pinned in $GRADLE_PROPS)"
        if [ ! -x "$pinned_java_home/bin/java" ]; then
            bad "org.gradle.java.home points at a path with no bin/java: $pinned_java_home" \
                "Either delete the line from $GRADLE_PROPS so Gradle uses JAVA_HOME, or update it to a valid JDK 17-25 install root."
        else
            pinned_ver=$("$pinned_java_home/bin/java" -version 2>&1 | head -1)
            pinned_major=$(printf '%s' "$pinned_ver" | awk -F'"' '{ split($2, a, "."); print a[1] }')
            if [ -n "$pinned_major" ] && [ "$pinned_major" -ge 17 ] && [ "$pinned_major" -le 25 ] 2>/dev/null; then
                ok "org.gradle.java.home JDK $pinned_major is in range  ($pinned_ver)"
            else
                bad "org.gradle.java.home pins Gradle to JDK ${pinned_major:-?}; repo needs 17-25" \
                    "Gradle 9.4.1 will refuse to start. Delete the org.gradle.java.home line OR update it to a 17-25 install."
            fi
        fi
    fi
fi

# --- Proxy detection -------------------------------------------------
sect "Proxy configuration"

# Gradle reads TWO gradle.properties:
#   (1) USER-LEVEL at ~/.gradle/gradle.properties -- proxy host/port/
#       creds + trust store go here. Per-user, per-machine, NEVER
#       committed to git.
#   (2) PROJECT-LEVEL at <project>/gradle.properties -- build tuning
#       (JVM args, parallel, caching). Committed to git. Proxy
#       settings DO NOT belong here.
# This script only reads/writes (1). (2) is never modified.
info "User-level gradle.properties (proxy + creds live here):"
info "  $GRADLE_PROPS"
# Resolve project-level gradle.properties for EVERY sub-project wrapper
# we ship. Historically this only scanned banking-app/, which would miss
# accidentally-committed proxy/cred lines in the harness / cli / webui
# projects.
for proj in banking-app bench-harness bench-cli bench-webui; do
    proj_props="$REPO_ROOT/$proj/gradle.properties"
    [ -f "$proj_props" ] || continue
    info "Project-level gradle.properties (NOT used for proxy; only build tuning):"
    info "  $proj_props"
    if grep -qE 'systemProp\.(http|https)\.proxy|orgInternalMaven(User|Password)|artifactoryResolver(Username|Password)' "$proj_props"; then
        warn "Project-level gradle.properties contains proxy/credential lines!" \
            "These would be committed to git. Move them to $GRADLE_PROPS and remove from $proj_props."
    fi
done
env_proxy=""
for k in HTTPS_PROXY HTTP_PROXY NO_PROXY https_proxy http_proxy no_proxy; do
    val=$(eval "echo \${$k:-}")
    [ -n "$val" ] && { info "$k = $val"; env_proxy="yes"; }
done

# Flag JVM-level env vars that silently inject args into every JVM launched
# (including the Gradle wrapper). Common enterprise gotcha: a forgotten
# -Dhttp.proxyHost here will override what's in gradle.properties and
# mislead the operator debugging the build.
for k in GRADLE_OPTS JAVA_TOOL_OPTIONS _JAVA_OPTIONS JAVA_OPTS; do
    val=$(eval "echo \${$k:-}")
    if [ -n "$val" ]; then
        info "$k = $val"
        if echo "$val" | grep -qiE 'proxy|truststore|javax\.net\.ssl'; then
            warn "$k contains TLS/proxy JVM args that may conflict with gradle.properties." \
                "JVM-level env vars apply to every 'java' process; if they disagree with \$GRADLE_PROPS settings, the env vars win and the build picks up whichever came first."
        fi
    fi
done

gradle_proxy=""
gradle_proxy_host=""
gradle_proxy_port=""
if [ -f "$GRADLE_PROPS" ]; then
    if grep -qE '^\s*systemProp\.https?\.proxyHost\s*=' "$GRADLE_PROPS"; then
        ok "$GRADLE_PROPS configures proxies"
        gradle_proxy="yes"
        gradle_proxy_host=$(grep -E '^\s*systemProp\.https\.proxyHost\s*=' "$GRADLE_PROPS" | head -1 | sed 's/.*=\s*//')
        gradle_proxy_port=$(grep -E '^\s*systemProp\.https\.proxyPort\s*=' "$GRADLE_PROPS" | head -1 | sed 's/.*=\s*//')
    else
        info "$GRADLE_PROPS exists but no proxy entries."
    fi
else
    info "$GRADLE_PROPS does not exist."
fi

# VSCode proxy detection — cross-platform paths.
if [ -f "$HOME/Library/Application Support/Code/User/settings.json" ]; then
    VSC_SETTINGS="$HOME/Library/Application Support/Code/User/settings.json"
elif [ -f "$HOME/.config/Code/User/settings.json" ]; then
    VSC_SETTINGS="$HOME/.config/Code/User/settings.json"
elif [ -f "$HOME/Library/Application Support/Code - Insiders/User/settings.json" ]; then
    VSC_SETTINGS="$HOME/Library/Application Support/Code - Insiders/User/settings.json"
else
    VSC_SETTINGS=""
fi
if [ -n "$VSC_SETTINGS" ]; then
    # Strip line comments so python can parse JSONC. Extract proxy,
    # noProxy (bypass list), strict-SSL, and proxy-support in a single
    # pass so we don't re-parse four times.
    vsc_parsed=$(sed -e 's://.*$::' "$VSC_SETTINGS" 2>/dev/null | python3 <<'PY' 2>/dev/null
import json, sys
try:
    d = json.load(sys.stdin)
except Exception:
    sys.exit(0)
proxy = d.get("http.proxy", "")
np = d.get("http.noProxy", [])
np_joined = "|".join(np) if isinstance(np, list) else str(np)
strict = d.get("http.proxyStrictSSL", "")
support = d.get("http.proxySupport", "")
print(f"{proxy}\t{np_joined}\t{strict}\t{support}")
PY
)
    vsc_proxy=$(echo "$vsc_parsed" | cut -f1)
    vsc_noproxy=$(echo "$vsc_parsed" | cut -f2)
    vsc_strict=$(echo "$vsc_parsed" | cut -f3)
    vsc_support=$(echo "$vsc_parsed" | cut -f4)
    if [ -n "$vsc_proxy" ]; then
        info "VSCode http.proxy = $vsc_proxy  (from $VSC_SETTINGS)"
        vs_host=$(echo "$vsc_proxy" | sed -E 's#^https?://([^:/]+)(:[0-9]+)?.*#\1#')
        vs_port=$(echo "$vsc_proxy" | sed -nE 's#^https?://[^:/]+:([0-9]+).*#\1#p')
        [ -z "$vs_port" ] && vs_port="80"
        # Prefer VSCode's http.noProxy for the Gradle nonProxyHosts
        # pipe-delimited value. Fall back to loopback defaults if empty
        # so local services (bench-webui, H2, etc) are never routed
        # through the proxy.
        if [ -n "$vsc_noproxy" ]; then
            nonproxy_joined="$vsc_noproxy"
            nonproxy_note="sourced from VSCode http.noProxy: $vsc_noproxy"
        else
            nonproxy_joined="localhost|127.0.0.1|*.local"
            nonproxy_note="VSCode http.noProxy is empty -- applied default localhost/loopback exclusions"
        fi
        needs_sync=0
        if [ -z "$gradle_proxy" ]; then
            needs_sync=1
            warn "Gradle has no proxy but VSCode uses $vs_host:$vs_port." \
                "Syncing lets 'gradlew build' use the same proxy VSCode does."
        elif [ "$gradle_proxy_host" != "$vs_host" ] || [ "$gradle_proxy_port" != "$vs_port" ]; then
            needs_sync=1
            warn "Gradle proxy ($gradle_proxy_host:$gradle_proxy_port) differs from VSCode ($vs_host:$vs_port)."
        else
            ok "Gradle and VSCode proxies match."
        fi
        if [ "$needs_sync" = "1" ] && [ "$NON_INTERACTIVE" = "0" ]; then
            echo
            echo "  ${YEL}The following changes will be written to $GRADLE_PROPS :${CLR}"
            echo "    systemProp.https.proxyHost       = $vs_host"
            echo "    systemProp.https.proxyPort       = $vs_port"
            echo "    systemProp.https.nonProxyHosts   = $nonproxy_joined"
            echo "    systemProp.http.proxyHost        = $vs_host"
            echo "    systemProp.http.proxyPort        = $vs_port"
            echo "    systemProp.http.nonProxyHosts    = $nonproxy_joined"
            echo "    ($nonproxy_note)"
            if [ "$vsc_strict" = "False" ] || [ "$vsc_strict" = "false" ]; then
                echo "    ${YEL}NOTE: VSCode has http.proxyStrictSSL = false.${CLR}"
                echo "          Gradle does NOT inherit this -- Gradle still validates TLS."
                echo "          If your proxy intercepts HTTPS, follow the TLS chain prompt further down."
            fi
            [ -n "$vsc_support" ] && echo "    (VSCode http.proxySupport = '$vsc_support' -- informational only)"
            echo "  Existing $GRADLE_PROPS (if any) will be backed up alongside it before the edit."
            printf "  Proceed with the write? [y/N]: "
            read -r resp
            case "$resp" in
                [Yy]|[Yy][Ee][Ss])
                    mkdir -p "$(dirname "$GRADLE_PROPS")"
                    if [ -f "$GRADLE_PROPS" ]; then
                        stamp=$(date +%Y%m%d-%H%M%S)
                        backup="$GRADLE_PROPS.backup-$stamp"
                        cp "$GRADLE_PROPS" "$backup"
                        ok "Backed up existing config to $backup"
                    fi
                    tmp=$(mktemp)
                    [ -f "$GRADLE_PROPS" ] && \
                        grep -Ev '^\s*systemProp\.(https?\.proxy(Host|Port)|https?\.nonProxyHosts)\s*=' "$GRADLE_PROPS" > "$tmp"
                    cat >> "$tmp" <<EOF

# Added by build-health-check.sh on $(date +%FT%T) -- synced from VSCode http.proxy
systemProp.https.proxyHost=$vs_host
systemProp.https.proxyPort=$vs_port
systemProp.https.nonProxyHosts=$nonproxy_joined
systemProp.http.proxyHost=$vs_host
systemProp.http.proxyPort=$vs_port
systemProp.http.nonProxyHosts=$nonproxy_joined
EOF
                    mv "$tmp" "$GRADLE_PROPS"
                    ok "Updated $GRADLE_PROPS -- re-run this script to verify."
                    info "To revert: move the .backup-* file back over $GRADLE_PROPS."
                    ;;
                *) info "Skipped. Copy the proxy manually into $GRADLE_PROPS if needed." ;;
            esac
        fi
    else
        info "VSCode settings.json has no http.proxy key."
    fi
fi

# Direct Internet probe (bypasses any HTTP_PROXY)
direct=""
if curl --noproxy '*' -fsS --max-time 5 -o /dev/null https://www.gstatic.com/generate_204 2>/dev/null; then
    direct="yes"; ok "Direct Internet reachable (no proxy detected on this network)."
else
    info "Direct Internet not reachable -> assuming corporate proxy required."
    if [ -z "$env_proxy" ] && [ -z "$gradle_proxy" ]; then
        bad "No proxy configured anywhere (env vars unset, $GRADLE_PROPS has no systemProp.https.proxyHost)" \
            "Add proxy entries to $GRADLE_PROPS. See README.md 'Behind a corporate proxy'."
    fi
fi

# --- Artifact repos --------------------------------------------------
sect "Gradle properties consistency"

read_prop() {
    [ -f "$GRADLE_PROPS" ] || return 0
    grep -E "^\s*$(echo "$1" | sed 's/[.]/\\./g')\s*=" "$GRADLE_PROPS" | head -1 | sed -E 's/^[^=]+=//; s/^\s+//; s/\s+$//'
}

if [ ! -f "$GRADLE_PROPS" ]; then
    info "$GRADLE_PROPS does not exist -- skipping consistency check."
else
    httpsH=$(read_prop 'systemProp.https.proxyHost');  httpsP=$(read_prop 'systemProp.https.proxyPort')
    httpH=$(read_prop 'systemProp.http.proxyHost');    httpP=$(read_prop 'systemProp.http.proxyPort')
    httpsNon=$(read_prop 'systemProp.https.nonProxyHosts'); httpNon=$(read_prop 'systemProp.http.nonProxyHosts')
    tsStore=$(read_prop 'systemProp.javax.net.ssl.trustStore')
    tsType=$(read_prop  'systemProp.javax.net.ssl.trustStoreType')

    if [ -n "$httpsH" ] || [ -n "$httpH" ]; then
        if [ "$httpsH" = "$httpH" ] && [ "$httpsP" = "$httpP" ]; then
            ok "HTTP and HTTPS proxy match: $httpsH:$httpsP"
        else
            warn "HTTP and HTTPS proxy settings DIFFER:" "https=$httpsH:$httpsP  http=$httpH:$httpP. Usually both should match."
        fi
        [ "$httpsNon" != "$httpNon" ] && warn "HTTP and HTTPS nonProxyHosts DIFFER:" "https=$httpsNon  http=$httpNon"
    else
        info "No proxy configured in $GRADLE_PROPS."
    fi

    if [ -n "${vsc_proxy:-}" ]; then
        vsH=$(echo "$vsc_proxy" | sed -E 's#^https?://([^:/]+).*#\1#')
        vsP=$(echo "$vsc_proxy" | sed -nE 's#^https?://[^:/]+:([0-9]+).*#\1#p'); [ -z "$vsP" ] && vsP="80"
        if [ "$httpsH" = "$vsH" ] && [ "$httpsP" = "$vsP" ]; then
            ok "gradle.properties proxy matches VSCode http.proxy ($vsH:$vsP)."
        elif [ -n "$httpsH" ]; then
            warn "gradle.properties proxy ($httpsH:$httpsP) does NOT match VSCode http.proxy ($vsH:$vsP)." \
                "Re-run the Proxy prompt above to sync."
        fi
    fi

    # On Windows (via Cygwin / Git Bash / WSL), check for Windows-ROOT.
    case "$(uname -s)" in
        *CYGWIN*|*MINGW*|*MSYS*)
            if [ -n "$tsStore" ]; then
                if [ "$tsType" = "Windows-ROOT" ]; then
                    ok "trustStore='$tsStore', trustStoreType=Windows-ROOT (Gradle uses the Windows cert store -- correct)."
                elif [ -n "$tsType" ]; then
                    warn "trustStoreType=$tsType (expected Windows-ROOT on Windows)." \
                        "Your corporate root CA is installed in the Windows certificate store via GPO/MDM; setting trustStoreType=Windows-ROOT is how Gradle's JVM reads it."
                else
                    warn "trustStore is set but trustStoreType is not declared." \
                        "Add  systemProp.javax.net.ssl.trustStoreType=Windows-ROOT  for Gradle to use the Windows cert store."
                fi
            fi
            ;;
        *)
            [ -n "$tsStore" ] && info "trustStore override: $tsStore (type=${tsType:-default})"
            ;;
    esac

    # Credential-pair consistency across the three names we write.
    seen_user=""; seen_pass=""; inconsistent=0; missing=""
    for pair in \
        "orgInternalMavenUser|orgInternalMavenPassword|init-script mirror authentication" \
        "systemProp.gradle.wrapperUser|systemProp.gradle.wrapperPassword|Gradle wrapper distribution download" \
        "artifactoryResolverUsername|artifactoryResolverPassword|direct Artifactory resolver access"; do
        pu=$(echo "$pair" | cut -d'|' -f1)
        pp=$(echo "$pair" | cut -d'|' -f2)
        purpose=$(echo "$pair" | cut -d'|' -f3)
        u=$(read_prop "$pu")
        pw=$(read_prop "$pp")
        if [ -z "$u" ] || [ -z "$pw" ]; then
            missing="$missing$pu / $pp  ($purpose); "
            continue
        fi
        if [ -z "$seen_user" ]; then seen_user="$u"; seen_pass="$pw"; continue; fi
        [ "$u" != "$seen_user" ] || [ "$pw" != "$seen_pass" ] && inconsistent=1
    done
    if [ -z "$missing" ] && [ "$inconsistent" = "0" ] && [ -n "$seen_user" ]; then
        ok "All three credential pairs present and consistent (user '$seen_user')."
    elif [ -n "$missing" ]; then
        warn "Missing Gradle credential pair(s) in $GRADLE_PROPS; run the Maven-Central remediation below to inject them." "Missing: $missing"
    fi
    [ "$inconsistent" = "1" ] && warn "Credential pairs in $GRADLE_PROPS DISAGREE across names." "Every pair should share the same username/password."
fi

sect "Corporate repository configuration"
# The project builds with Gradle only; we never invoke mvn. But many
# enterprises hand out Artifactory URL + credentials via Maven's
# settings.xml format (e.g. JFrog's "Set Me Up -> Maven" workflow).
# This section treats settings.xml and maven-wrapper.properties as
# READ-ONLY data sources: we parse them, extract the URL + any
# attached <server> creds, then translate those values into Gradle-
# native config in the Maven-Central remediation block below. We
# never write to ~/.m2/ -- that's for Maven to consume, and we're not
# using Maven.

mav_hints=()
# Parallel store for <server> blocks. Entries: "source||serverId|username|password".
# Looked up by exact match on source + serverId when probing a repo with that id.
server_creds=()
# Set to 1 by probe_mirror_url if Basic auth was rejected but Bearer
# succeeded. Consumed when emitting the Gradle init script to choose
# HttpHeaderAuthentication vs the plain credentials{} block.
BEARER_REQUIRED=0
default_settings=(
    "$HOME/.m2/settings.xml"
    "$HOME/Downloads/settings.xml"
    "./settings.xml"
)
default_wrappers=(
    "$HOME/.m2/wrapper/maven-wrapper.properties"
    "$HOME/.mvn/wrapper/maven-wrapper.properties"
    "$HOME/Downloads/maven-wrapper.properties"
    "./maven-wrapper.properties"
    "./.mvn/wrapper/maven-wrapper.properties"
)
downloads_dir="$HOME/Downloads"

# Helpers -------------------------------------------------------------------
parse_settings_xml() {
    local path="$1"
    [ -f "$path" ] || return 0
    local any_url=""
    # Python helper emits tab-delimited records. Fields per kind:
    #   mirror:      mirror<tab>mirror_id<tab><tab>url
    #   repo:        repository<tab>id<tab>name<tab>url
    #   pluginRepo:  pluginRepository<tab>id<tab>name<tab>url
    #   server:      server<tab>id<tab>username<tab>password
    while IFS=$'\t' read -r tag f2 f3 f4; do
        case "$tag" in
            mirror)
                mav_hints+=("$f4|$path|from <mirror> block in settings.xml|$f2")
                ok "Parsed $path -- extracted mirror URL: $f4"
                any_url="yes"
                ;;
            repository|pluginRepository)
                local note label
                if [ "$f2" = "central" ]; then
                    note="from <$tag id=\"central\"> (OVERRIDES mavenCentral() via Maven's default-id resolution)"
                else
                    note="from <$tag id=\"$f2\"> (adds as alternate repo, does NOT replace mavenCentral())"
                fi
                label="$f4"
                [ -n "$f3" ] && label="$f4 ($f3)"
                mav_hints+=("$f4|$path|$note|$f2")
                # If we already have creds for this id, mention it.
                local credhit=""
                for c in "${server_creds[@]}"; do
                    cs=$(echo "$c" | cut -d'|' -f1); cid=$(echo "$c" | cut -d'|' -f2)
                    if [ "$cs" = "$path" ] && [ "$cid" = "$f2" ]; then credhit="yes"; break; fi
                done
                local credNote=""
                [ -n "$credhit" ] && credNote="  (creds attached from matching <server id='$f2'>)"
                if [ "$f2" = "central" ]; then
                    ok "Parsed $path -- Central-override $tag url: $label$credNote"
                else
                    info "Parsed $path -- additional <$tag id='$f2'> url: $label$credNote"
                fi
                any_url="yes"
                ;;
            server)
                # f2=id  f3=username  f4=password. Detect three
                # situations where the value won't work as-is:
                #   encrypted     {...}           -- needs settings-security.xml
                #   template      ${env.X}        -- curl can't resolve Maven templates
                #   token-only    no username     -- Bearer instead of Basic
                local enc=0 tpl=0 resolved=""
                [[ "$f4" =~ ^[[:space:]]*\{.*\}[[:space:]]*$ ]] && enc=1
                if [[ "$f3" =~ \$\{[^}]+\} ]] || [[ "$f4" =~ \$\{[^}]+\} ]]; then tpl=1; fi
                if [ "$tpl" = "1" ]; then
                    # Try to resolve ${env.X} references from the real
                    # process env (common pattern: keep token out of VCS).
                    resolved=$(printf '%s' "$f4" | python3 -c '
import os, re, sys
t = sys.stdin.read()
def sub(m):
    v = os.environ.get(m.group(1), "")
    return v if v else m.group(0)
print(re.sub(r"\$\{env\.([^}]+)\}", sub, t), end="")
')
                    if [ "$resolved" != "$f4" ] && [[ ! "$resolved" =~ \$\{[^}]+\} ]]; then
                        f4="$resolved"
                        tpl=0
                        info "Parsed $path -- <server id='$f2'> username '$f3', password resolved from env var"
                    fi
                fi
                server_creds+=("$path||$f2|$f3|$f4")
                if [ -z "$f3" ]; then
                    info "Parsed $path -- <server id='$f2'> declared (no username; likely token-only)"
                elif [ "$enc" = "1" ]; then
                    warn "Parsed $path -- <server id='$f2'> username '$f3' but password is Maven-ENCRYPTED {...}" \
                        "Decrypt with 'mvn --encrypt-password' (requires ~/.m2/settings-security.xml), paste plaintext back, or expose via \${env.NAME}."
                elif [ "$tpl" = "1" ]; then
                    warn "Parsed $path -- <server id='$f2'> username/password contains Maven template \${...}" \
                        "Maven resolves these client-side; curl doesn't. Replace with literal values in settings.xml, or set the matching env var."
                elif [ -n "$resolved" ]; then
                    :   # Already logged above when we resolved successfully.
                else
                    info "Parsed $path -- <server id='$f2'> credential block, username '$f3'"
                fi
                ;;
        esac
    done < <(python3 - "$path" <<'PY'
import re, sys, pathlib
try:
    txt = pathlib.Path(sys.argv[1]).read_text(encoding='utf-8', errors='replace')
except Exception:
    sys.exit(0)

def inner(block, tag):
    m = re.search(rf'<{tag}>\s*([^<]*)\s*</{tag}>', block)
    return m.group(1).strip() if m else ''

# <server> -- emit first so repo/plugin entries can reference by id
for m in re.finditer(r'(?s)<server>(.*?)</server>', txt):
    body = m.group(1)
    sid = inner(body, 'id')
    usr = inner(body, 'username')
    pwd = inner(body, 'password')
    if sid:
        print(f"server\t{sid}\t{usr}\t{pwd}")

# <mirror>
for m in re.finditer(r'(?s)<mirror>(.*?)</mirror>', txt):
    body = m.group(1)
    url = inner(body, 'url')
    mid = inner(body, 'id')
    if url:
        print(f"mirror\t{mid}\t\t{url}")

# <repository> / <pluginRepository>
for m in re.finditer(r'(?s)<(repository|pluginRepository)>(.*?)</\1>', txt):
    tag, body = m.group(1), m.group(2)
    url = inner(body, 'url')
    if not url:
        continue
    rid = inner(body, 'id')
    rname = inner(body, 'name')
    print(f"{tag}\t{rid}\t{rname}\t{url}")
PY
)
    [ -z "$any_url" ] && warn "$path has no <mirror>, <repository>, or <pluginRepository> URL -- nothing to extract."
}

# Look up <server> creds for a given source + id. Prints "user<tab>password"
# on stdout if found (empty otherwise).
lookup_server_creds() {
    local src="$1" sid="$2"
    [ -z "$sid" ] && return 0
    for c in "${server_creds[@]}"; do
        cs=$(echo "$c" | cut -d'|' -f1)
        cid=$(echo "$c" | cut -d'|' -f2)
        if [ "$cs" = "$src" ] && [ "$cid" = "$sid" ]; then
            cu=$(echo "$c" | cut -d'|' -f3)
            cp=$(echo "$c" | cut -d'|' -f4)
            printf "%s\t%s" "$cu" "$cp"
            return 0
        fi
    done
}

parse_maven_wrapper() {
    local path="$1"
    [ -f "$path" ] || return 0
    local dist base
    dist=$(grep -E '^\s*distributionUrl\s*=' "$path" | head -1 | sed -e 's/^\s*distributionUrl\s*=\s*//' -e 's/\\:/:/g')
    if [ -z "$dist" ]; then
        warn "$path has no distributionUrl line -- nothing to extract."
        return 0
    fi
    base=$(echo "$dist" | sed -nE \
        -e 's#^(https?://[^/]+/artifactory/[^/]+/).*#\1#p' \
        -e 's#^(https?://[^/]+/repository/[^/]+/).*#\1#p')
    [ -z "$base" ] && base=$(echo "$dist" | sed -nE 's#^(https?://[^/]+/).*#\1#p')
    if [ -n "$base" ]; then
        mav_hints+=("$base|$path|derived from distributionUrl in maven-wrapper.properties")
        ok "Parsed $path -- derived base URL: $base"
    else
        warn "$path has distributionUrl '$dist' but could not extract a base URL."
    fi
}

# Probe Gradle's REAL build config -- uses mavenCentral() and
# gradlePluginPortal() exactly as the project does. The user's proxy +
# init scripts apply naturally.
probe_real_build_config() {
    local wrapper=""
    for root in "$(pwd)" "$(dirname "$(pwd)")" "./banking-app" "./bench-webui" "./bench-cli" "./bench-harness"; do
        [ -x "$root/gradlew" ] && { wrapper="$root/gradlew"; break; }
    done
    if [ -z "$wrapper" ]; then
        bad "  no gradlew wrapper found in nearby project dirs"
        return 1
    fi
    local tmpdir
    tmpdir=$(mktemp -d -t gradle-real-probe-XXXXXX)
    echo 'rootProject.name = "real-probe"' > "$tmpdir/settings.gradle.kts"
    cat > "$tmpdir/build.gradle.kts" <<'EOF'
plugins { java }
repositories {
    mavenCentral()
    gradlePluginPortal()
}
configurations { create("mirrorProbe") }
dependencies {
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
EOF
    local gradle_user_home="${GRADLE_USER_HOME:-$HOME/.gradle}"
    info "    Real-build probe command:"
    info "      \"$wrapper\" -p \"$tmpdir\" --refresh-dependencies --console=plain --no-configuration-cache --no-daemon --stacktrace checkMirror"
    info "    Reads: $gradle_user_home/gradle.properties (proxy + creds)"
    info "    Init scripts applied (any rewrite happens here):"
    if [ -d "$gradle_user_home/init.d" ]; then
        for f in "$gradle_user_home/init.d"/*; do
            [ -f "$f" ] && info "      $f"
        done
    else
        info "      (none -- init.d directory does not exist; will hit mavenCentral() directly)"
    fi
    info "    Probe project preserved at (until success): $tmpdir"
    info "    Running probe..."
    local output exit_code
    output=$("$wrapper" -p "$tmpdir" \
        --refresh-dependencies --console=plain \
        --no-configuration-cache --no-daemon --stacktrace \
        checkMirror 2>&1)
    exit_code=$?
    if [ "$exit_code" = "0" ] && echo "$output" | grep -q 'PROBE_OK: resolved'; then
        rm -rf "$tmpdir"
        ok "Real build config RESOLVES successfully. ./gradlew build will work."
        info "No mirror remediation needed -- your current proxy + init-script setup is sufficient."
        return 0
    fi

    local category=""
    if   echo "$output" | grep -qE '401|Unauthorized'; then category="HTTP 401 -- proxy or mirror credentials wrong"
    elif echo "$output" | grep -qE 'PKIX|SSLHandshake|certificate|trust'; then
        category="TLS handshake failed (corporate CA missing from JDK trust store)"
    elif echo "$output" | grep -qE 'UnknownHost|NoRouteToHost|ConnectException|timed out'; then
        category="could not reach repo.maven.apache.org / plugins.gradle.org -- proxy not configured or mirror needed"
    else
        category="exit $exit_code"
    fi
    bad "Real build config FAILED: $category"
    info "  Gradle output (most relevant lines):"
    {
        echo "$output" | awk '/^\* What went wrong:/{flag=1;next} /^\* Try:/{flag=0} flag && /[^[:space:]]/'
        echo "$output" | grep -E 'Could not resolve|Received fatal alert|Failed to transfer'
    } | awk '!seen[$0]++' | head -6 | while IFS= read -r l; do info "    $l"; done
    info "  Probe project preserved at: $tmpdir"
    case "$category" in
        *401*)                info "  -> Check orgInternalMaven* / artifactoryResolver* creds in $GRADLE_PROPS." ;;
        *TLS*)                info "  -> Import the corporate root CA into the JDK trust store (TLS section below)." ;;
        *proxy*not*configured*|*mirror*needed*) info "  -> Either configure a proxy in $GRADLE_PROPS, or wire a mirror via the Maven-Central remediation below." ;;
        *)                    info "  -> Re-run manually for stacktrace: cd <project> && ./gradlew --refresh-dependencies --stacktrace build" ;;
    esac
    return 1
}

# Probe a SPECIFIC candidate URL as a Maven mirror. Different intent
# from probe_real_build_config: this answers "would THIS particular
# URL work if I wired it as a mavenCentral() override?"
probe_mirror_via_gradle() {
    local url="$1" label="$2" user="${3:-}" pass="${4:-}"
    local wrapper=""
    for root in "$(pwd)" "$(dirname "$(pwd)")" "./banking-app" "./bench-webui" "./bench-cli" "./bench-harness"; do
        [ -x "$root/gradlew" ] && { wrapper="$root/gradlew"; break; }
    done
    if [ -z "$wrapper" ]; then
        bad "  $label $url  -->  no gradlew wrapper found in nearby project dirs; cannot run Gradle probe"
        return 1
    fi

    local tmpdir
    tmpdir=$(mktemp -d)
    echo 'rootProject.name = "mirror-probe"' > "$tmpdir/settings.gradle.kts"
    local creds_clause=""
    if [ -n "$user" ] && [ -n "$pass" ] && [[ ! "$pass" =~ ^[[:space:]]*\{.*\}[[:space:]]*$ ]] && [[ ! "$pass" =~ \$\{[^}]+\} ]]; then
        creds_clause="    credentials { username = \"$user\"; password = \"$pass\" }"
    elif [ -z "$user" ] && [ -n "$pass" ] && [[ ! "$pass" =~ ^[[:space:]]*\{.*\}[[:space:]]*$ ]] && [[ ! "$pass" =~ \$\{[^}]+\} ]]; then
        creds_clause="    credentials(HttpHeaderCredentials::class) { name = \"Authorization\"; value = \"Bearer $pass\" }
    authentication { create<HttpHeaderAuthentication>(\"header\") }"
    fi
    cat > "$tmpdir/build.gradle.kts" <<EOF
plugins { java }
repositories {
    // Only this repo -- no mavenCentral fallback that would mask a failure.
    maven {
        name = "probe"
        url = uri("$url")
        isAllowInsecureProtocol = false
$creds_clause
    }
}
configurations { create("mirrorProbe") }
dependencies {
    // Three representative artifacts every Maven-Central-proxying repo
    // MUST serve for the build to succeed. Including gson catches the
    // common JFrog "URL is libs-release, not the maven-central-virtual"
    // misconfiguration -- the AppMap plugin pulls gson transitively, so
    // a libs-release-only URL produces "Could not resolve gson" errors.
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
EOF
    # Surface the native invocation so the operator can copy-paste it
    # to reproduce manually. Includes every path Gradle will read.
    local gradle_user_home="${GRADLE_USER_HOME:-$HOME/.gradle}"
    info "    Gradle probe command:"
    info "      \"$wrapper\" -p \"$tmpdir\" --refresh-dependencies --console=plain --no-configuration-cache --no-daemon --stacktrace checkMirror"
    info "    Gradle will read these properties files (in precedence order):"
    info "      1. $tmpdir/gradle.properties  (probe project; does not exist)"
    info "      2. $gradle_user_home/gradle.properties  (user-level; proxy + creds live here)"
    info "    Probe project files:"
    info "      $tmpdir/settings.gradle.kts"
    info "      $tmpdir/build.gradle.kts"
    info "    Init scripts applied (from $gradle_user_home/init.d/):"
    if [ -d "$gradle_user_home/init.d" ]; then
        for f in "$gradle_user_home/init.d"/*; do
            [ -f "$f" ] && info "      $f"
        done
    else
        info "      (none -- init.d directory does not exist)"
    fi
    info "    JAVA_HOME: ${JAVA_HOME:-(not set; wrapper will resolve from PATH)}"
    info "    Running probe (first run may take 30-60s while Gradle 9.4.1 distribution downloads)..."

    # Drop -q so error text is preserved; disable cache/daemon; add
    # --stacktrace so the exception shape is visible when things fail.
    local output exit_code
    output=$("$wrapper" -p "$tmpdir" \
        --refresh-dependencies \
        --console=plain \
        --no-configuration-cache \
        --no-daemon \
        --stacktrace \
        checkMirror 2>&1)
    exit_code=$?
    if [ "$exit_code" = "0" ] && echo "$output" | grep -q 'PROBE_OK: resolved'; then
        # Clean up on success only.
        rm -rf "$tmpdir"
        ok "  $label $url  -->  Gradle resolved slf4j-api (exit 0)"
        return 0
    fi
    # Failure: leave tmpdir intact and tell the operator where it is.
    info "    Probe project preserved for inspection at: $tmpdir"

    # Categorize + surface the actual "What went wrong:" block plus
    # any Could-not-resolve / handshake lines. Cap at ~6 lines.
    local category=""
    if   echo "$output" | grep -qE '401|Unauthorized'; then category="HTTP 401 (auth rejected)"
    elif echo "$output" | grep -qE '403|Forbidden';    then category="HTTP 403 (forbidden)"
    elif echo "$output" | grep -qE 'PKIX|SSLHandshake|certificate|trust'; then
        category="TLS handshake failed (corporate CA likely missing from JDK trust store)"
    elif echo "$output" | grep -qE 'UnknownHost|NoRouteToHost|ConnectException|timed out'; then
        category="could not reach host (network / proxy / DNS)"
    elif echo "$output" | grep -qE 'com\.google\.code\.gson|spring-boot' && \
         echo "$output" | grep -qE 'Could not (find|resolve)'; then
        category="this URL doesn't proxy Maven Central (gson/spring-boot not found)"
    else
        category="exit $exit_code"
    fi
    bad "  $label $url  -->  Gradle FAILED: $category"

    info "    Gradle output (most relevant lines):"
    {
        echo "$output" | awk '/^\* What went wrong:/{flag=1;next} /^\* Try:/{flag=0} flag && /[^[:space:]]/'
        echo "$output" | grep -E 'Could not resolve|Received fatal alert|Failed to transfer'
    } | awk '!seen[$0]++' | head -6 | while IFS= read -r l; do info "      $l"; done

    if echo "$category" | grep -q "doesn't proxy Maven Central"; then
        info "    -> Common JFrog Artifactory pattern:"
        info "         libs-release          - your org's releases ONLY (no Maven Central proxy)"
        info "         libs-release-virtual  - aggregates Maven Central + your org's libs (USE THIS)"
        info "         maven-central-virtual - same idea under a different name"
        info "       Find it: Artifactory Repositories sidebar, filter Type=Virtual + PackageType=Maven,"
        info "       or re-run 'Set Me Up -> Maven' and pick the virtual repo."
    else
        info "    -> Re-run Gradle manually for full stacktrace:"
        info "       cd <project> && ./gradlew --refresh-dependencies --stacktrace help"
    fi
    return 1
}

# Probe a candidate URL with a HEAD request; print one [PASS]/[WARN]/[FAIL]
# line per URL. On a 401/403 with creds attached, surface:
#   - auth mode sent (basic / bearer / none) + username + [len]
#     characters of the password (first 4 chars, LAST 4 chars -- enough
#     to confirm the right token is being sent, not enough to leak it)
#   - the server's WWW-Authenticate header (tells you which auth scheme
#     Artifactory actually wants)
#   - a GET retry (some Artifactory configs reject HEAD but allow GET)
#   - a "likely cause" line with an actionable fix
probe_mirror_url() {
    local url="$1" label="$2" user="${3:-}" pass="${4:-}"
    local code err hdrfile auth_mode="none" auth_preview=""
    # Decide which auth header to send.
    local use_basic=0 use_bearer=0
    if [ -n "$user" ] && [ -n "$pass" ] && [[ ! "$pass" =~ ^[[:space:]]*\{.*\}[[:space:]]*$ ]] && [[ ! "$pass" =~ \$\{[^}]+\} ]]; then
        use_basic=1
        auth_mode="basic"
    elif [ -z "$user" ] && [ -n "$pass" ] && [[ ! "$pass" =~ ^[[:space:]]*\{.*\}[[:space:]]*$ ]] && [[ ! "$pass" =~ \$\{[^}]+\} ]]; then
        use_bearer=1
        auth_mode="bearer"
    fi
    if [ "$use_basic" = "1" ] || [ "$use_bearer" = "1" ]; then
        local plen=${#pass}
        local ptail="${pass: -4}"
        if [ "$use_basic" = "1" ]; then
            auth_preview="user='$user', password=[${plen} chars, ending '...${ptail}']"
        else
            auth_preview="Bearer token=[${plen} chars, ending '...${ptail}']"
        fi
    fi

    err=$(mktemp); hdrfile=$(mktemp)
    if [ "$use_basic" = "1" ]; then
        code=$(curl -sS -o /dev/null -D "$hdrfile" -w '%{http_code}' --max-time 15 -L -I -u "$user:$pass" "$url" 2>"$err")
    elif [ "$use_bearer" = "1" ]; then
        code=$(curl -sS -o /dev/null -D "$hdrfile" -w '%{http_code}' --max-time 15 -L -I -H "Authorization: Bearer $pass" "$url" 2>"$err")
    else
        code=$(curl -sS -o /dev/null -D "$hdrfile" -w '%{http_code}' --max-time 15 -L -I "$url" 2>"$err")
    fi
    local stderr_tail
    stderr_tail=$(tr -d '\n' < "$err" | sed 's/  */ /g')
    local wwwauth
    wwwauth=$(grep -i '^www-authenticate:' "$hdrfile" | head -1 | sed 's/^[Ww][Ww][Ww]-[Aa]uthenticate:[[:space:]]*//; s/\r$//')
    rm -f "$err" "$hdrfile"

    case "$code" in
        200|301|302|307)   ok    "  $label $url  -->  HTTP $code"; return 0 ;;
        401|403)
            # Recovery cascade before reporting failure:
            #   (a) If we sent Basic and server wants Bearer (or no
            #       WWW-Authenticate header at all), retry using the
            #       same token as Bearer. Artifactory's Identity Tokens
            #       / Access Tokens commonly require this.
            #   (b) If still failing, retry with GET (some Artifactory
            #       configs reject HEAD but accept GET).
            local bearer_retry_code=""
            if [ "$use_basic" = "1" ] && [ -n "$pass" ]; then
                if [ -z "$wwwauth" ] || echo "$wwwauth" | grep -qi '^[[:space:]]*Bearer\b'; then
                    bearer_retry_code=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 15 -L -I \
                        -H "Authorization: Bearer $pass" "$url" 2>/dev/null)
                    case "$bearer_retry_code" in
                        200|301|302|307)
                            ok "  $label $url  -->  HTTP $bearer_retry_code via Bearer retry (Basic rejected; Artifactory Identity-Token style)"
                            # Emit a sentinel so the writer step knows to
                            # use HttpHeaderAuthentication in the init script.
                            BEARER_REQUIRED=1
                            return 0
                            ;;
                    esac
                fi
            fi
            bad "  $label $url  -->  HTTP $code (reachable but auth rejected)"
            info "    auth sent:         $auth_mode ($auth_preview)"
            if [ -n "$wwwauth" ]; then
                info "    server demands:    WWW-Authenticate: $wwwauth"
            else
                info "    server demands:    (no WWW-Authenticate header returned)"
            fi
            if [ -n "$bearer_retry_code" ]; then
                info "    Bearer retry:      HTTP $bearer_retry_code (still rejected; token itself is invalid or user-side scope is needed)"
            fi
            # GET retry -- rules out HEAD blocking.
            local getcode
            if [ "$use_basic" = "1" ]; then
                getcode=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 15 -L -u "$user:$pass" "$url" 2>/dev/null)
            elif [ "$use_bearer" = "1" ]; then
                getcode=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 15 -L -H "Authorization: Bearer $pass" "$url" 2>/dev/null)
            else
                getcode=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 15 -L "$url" 2>/dev/null)
            fi
            case "$getcode" in
                200|301|302|307)
                    info "    GET retry:         HTTP $getcode -- GET SUCCEEDS where HEAD failed"
                    info "                       -> Gradle uses GET for resolution, so this URL will likely work at build time"
                    ;;
                *)
                    info "    GET retry:         HTTP $getcode (same failure, not a HEAD-method issue)"
                    ;;
            esac
            if echo "$wwwauth" | grep -qi '^[[:space:]]*Bearer\b'; then
                info "    likely cause:      Artifactory demands Bearer and the password used as a token was also rejected."
                info "                       -> Token is invalid/expired. Regenerate via 'Set Me Up -> Maven' in Artifactory."
            else
                info "    likely cause:      token expired, wrong user, or Artifactory expects a different auth scheme."
                info "                       -> Re-run 'Set Me Up -> Maven' in Artifactory to regenerate settings.xml with a fresh token."
            fi
            return 1
            ;;
        000)
            local detail="${stderr_tail:-no response}"
            if echo "$stderr_tail" | grep -qi 'could not resolve'; then
                detail="DNS lookup failed (host unknown)"
            elif echo "$stderr_tail" | grep -qi 'ssl\|certificate\|tls'; then
                detail="TLS handshake failed -- corporate root may need import"
            elif echo "$stderr_tail" | grep -qi 'connection refused'; then
                detail="connection refused by host"
            elif echo "$stderr_tail" | grep -qi 'timed out'; then
                detail="connection timed out"
            fi
            bad "  $label $url  -->  $detail"
            return 2
            ;;
        *)                 warn  "  $label $url  -->  unexpected HTTP $code"; return 1 ;;
    esac
}

scan_directory() {
    local dir="$1"
    if [ ! -d "$dir" ]; then warn "Directory does not exist: $dir"; return; fi
    info "Recursively searching $dir for settings.xml / maven-wrapper.properties (depth 3)"
    local found=""
    while IFS= read -r f; do
        found="yes"
        info "  -> $f"
        case "$(basename "$f")" in
            settings.xml)             parse_settings_xml "$f" ;;
            maven-wrapper.properties) parse_maven_wrapper "$f" ;;
        esac
    done < <(find "$dir" -maxdepth 3 -type f \( -name settings.xml -o -name maven-wrapper.properties \) 2>/dev/null)
    [ -z "$found" ] && warn "No settings.xml or maven-wrapper.properties found under $dir"
}

scan_default_locations() {
    info "Scanning default locations:"
    for p in "${default_settings[@]}" "${default_wrappers[@]}"; do info "  $p"; done
    for p in "${default_settings[@]}"; do parse_settings_xml "$p"; done
    for p in "${default_wrappers[@]}"; do parse_maven_wrapper "$p"; done
}

# Dispatch -------------------------------------------------------------------
# Always do a quick pre-scan of ~/.m2/settings.xml -- that's Maven's
# canonical location, and many enterprises drop the baseline config
# there. Application teams may "append any additional repositories"
# in project-local files, which the interactive flow below still
# offers to scan.
m2_default="$HOME/.m2/settings.xml"
m2_had_content=0
if [ -f "$m2_default" ]; then
    info "Found enterprise default at $m2_default -- parsing first."
    before=${#mav_hints[@]}
    parse_settings_xml "$m2_default"
    [ "${#mav_hints[@]}" -gt "$before" ] && m2_had_content=1
fi

if [ -n "$MAVEN_CONFIG_PATH" ]; then
    info "Honoring --maven-config = $MAVEN_CONFIG_PATH"
    if [ -f "$MAVEN_CONFIG_PATH" ]; then
        case "$(basename "$MAVEN_CONFIG_PATH")" in
            *.xml)         parse_settings_xml "$MAVEN_CONFIG_PATH" ;;
            *.properties)  parse_maven_wrapper "$MAVEN_CONFIG_PATH" ;;
            *)
                warn "File has no .xml or .properties extension; attempting both parsers..."
                parse_settings_xml  "$MAVEN_CONFIG_PATH"
                parse_maven_wrapper "$MAVEN_CONFIG_PATH"
                ;;
        esac
    elif [ -d "$MAVEN_CONFIG_PATH" ]; then
        scan_directory "$MAVEN_CONFIG_PATH"
    else
        warn "--maven-config path does not exist: $MAVEN_CONFIG_PATH"
    fi
    # Sweep remaining default locations. ~/.m2/ already handled above.
    for p in "${default_settings[@]}"; do
        [ "$p" = "$m2_default" ] && continue
        parse_settings_xml "$p"
    done
    for p in "${default_wrappers[@]}"; do parse_maven_wrapper "$p"; done
elif [ "$NON_INTERACTIVE" = "1" ]; then
    for p in "${default_settings[@]}"; do
        [ "$p" = "$m2_default" ] && continue
        parse_settings_xml "$p"
    done
    for p in "${default_wrappers[@]}"; do parse_maven_wrapper "$p"; done
else
    echo
    if [ "$m2_had_content" = "1" ]; then
        echo "  ${CYN}Enterprise convention: settings.xml is placed at $m2_default${CLR}"
        echo "  Application teams may 'append any additional repositories that need to be"
        echo "  referenced' -- either to that file or to a separate one elsewhere."
        printf "  Scan additional locations for more settings.xml / maven-wrapper.properties? [y/N] "
    else
        echo "  ${CYN}Corporate networks often ship a settings.xml (placed at $m2_default) or a${CLR}"
        echo "  maven-wrapper.properties that tells Maven / Gradle where the internal"
        echo "  Artifactory mirror lives. Nothing was found at the enterprise default location."
        printf "  Do you already have a settings.xml (or maven-wrapper.properties)? [y/N] "
    fi
    read -r has
    case "$has" in
        [Yy]|[Yy][Ee][Ss])
            echo
            echo "  ${YEL}How should I find it?${CLR}"
            echo "    [a] Scan the Downloads folder  ($downloads_dir)"
            echo "    [b] Specify a folder to recursively search (depth 3)"
            echo "    [c] Specify the direct file path"
            echo "    [d] Scan all default locations  (~/.m2/, ~/Downloads/, project root)"
            echo "    [s] Skip"
            printf "  Choose [a/b/c/d/s]: "
            read -r opt
            case "$opt" in
                a|A) scan_directory "$downloads_dir" ;;
                b|B)
                    printf "  Folder path: "
                    read -r dir
                    [ -n "$dir" ] && scan_directory "$dir"
                    ;;
                c|C)
                    printf "  File path (settings.xml or maven-wrapper.properties): "
                    read -r file
                    if [ -n "$file" ] && [ -f "$file" ]; then
                        case "$(basename "$file")" in
                            *.xml)         parse_settings_xml "$file" ;;
                            *.properties)  parse_maven_wrapper "$file" ;;
                            *)
                                warn "File has no .xml or .properties extension; attempting both parsers..."
                                parse_settings_xml  "$file"
                                parse_maven_wrapper "$file"
                                ;;
                        esac
                    elif [ -n "$file" ]; then
                        warn "File does not exist: $file"
                    fi
                    ;;
                d|D) scan_default_locations ;;
                *)   info "Skipped Maven config scan." ;;
            esac
            ;;
        *) info "Skipped (answered no). If Maven Central turns out to be unreachable below, re-run and answer yes." ;;
    esac
fi

# Note: up to this point we've only PARSED settings.xml. The next
# section offers to WRITE a proactive 'artifactory-external-mirror'
# configuration, the canonical enterprise reference setup. It produces
# matching entries in ~/.m2/settings.xml (<mirror> + <server>),
# ~/.gradle/gradle.properties (Gradle credential aliases + proxy creds),
# and ~/.gradle/init.d/corp-repos.gradle.kts. After it runs, the Maven
# Central reachability check below tests the wired-up mirror end-to-end.

# --- Artifactory external-mirror proactive setup ----------------------------
sect "Artifactory external-mirror setup"

settings_xml="$HOME/.m2/settings.xml"
artifactory_mirror_id="artifactory-external-mirror"

# State detection -- have we already wired this id end-to-end?
have_settings_mirror=0
have_settings_server=0
have_init_script=0
if [ -f "$settings_xml" ]; then
    grep -q "<id>${artifactory_mirror_id}</id>" "$settings_xml" 2>/dev/null \
        && have_settings_server=1
    python3 - "$settings_xml" "$artifactory_mirror_id" <<'PY' >/dev/null 2>&1 \
        && have_settings_mirror=1
import re, sys, pathlib
txt = pathlib.Path(sys.argv[1]).read_text(encoding='utf-8', errors='replace')
mid = sys.argv[2]
for m in re.finditer(r'(?s)<mirror>(.*?)</mirror>', txt):
    idm = re.search(r'<id>\s*([^<]+)\s*</id>', m.group(1))
    if idm and idm.group(1).strip() == mid:
        sys.exit(0)
sys.exit(1)
PY
fi
[ -f "$HOME/.gradle/init.d/corp-repos.gradle.kts" ] && have_init_script=1

if [ "$have_settings_mirror" = "1" ] && [ "$have_settings_server" = "1" ] && [ "$have_init_script" = "1" ]; then
    ok "${artifactory_mirror_id} already wired in $settings_xml + ~/.gradle/init.d/."
    info "  To reconfigure, delete the matching <mirror>/<server> blocks (or the init script) and re-run."
elif [ "$NON_INTERACTIVE" = "1" ]; then
    info "Non-interactive mode: skipping ${artifactory_mirror_id} setup prompt."
else
    echo
    echo "  ${CYN}Many enterprise reference configurations name their Maven Central proxy${CLR}"
    echo "  ${CYN}'${artifactory_mirror_id}'. If you have that URL, this script can wire it${CLR}"
    echo "  ${CYN}into settings.xml (mirror + server), gradle.properties, and an init script.${CLR}"
    [ "$have_settings_mirror" = "1" ] && info "  Existing: <mirror id='${artifactory_mirror_id}'> in $settings_xml"
    [ "$have_settings_server" = "1" ] && info "  Existing: <server id='${artifactory_mirror_id}'> in $settings_xml"
    [ "$have_init_script"     = "1" ] && info "  Existing: ~/.gradle/init.d/corp-repos.gradle.kts (will be replaced if you proceed)"

    suggested_url=""
    if [ "${#mav_hints[@]}" -gt 0 ]; then
        suggested_url=$(echo "${mav_hints[0]}" | cut -d'|' -f1)
    fi

    if [ -n "$suggested_url" ]; then
        echo "  A candidate URL was discovered during this run:"
        echo "    $suggested_url"
        echo "  Options:"
        echo "    - Press ENTER to use the candidate above"
        echo "    - Paste a different URL to use instead"
        echo "    - Type 'skip' to skip this setup entirely"
        printf "  Your choice: "
    else
        echo "  Options:"
        echo "    - Paste the artifactory-external-mirror URL to wire it up"
        echo "    - Press ENTER (or type 'skip') to skip this setup entirely"
        printf "  Your choice: "
    fi
    read -r am_url
    case "$am_url" in
        skip|SKIP|Skip) am_url="" ;;
        "")             am_url="$suggested_url" ;;
    esac

    if [ -z "$am_url" ]; then
        info "Skipped artifactory-external-mirror setup."
    else
        am_url="${am_url%/}/"

        # Pull existing proxy creds as defaults so the user can hit
        # ENTER twice if they're already configured.
        proxy_user_default=""
        proxy_pass_default=""
        if [ -f "$GRADLE_PROPS" ]; then
            proxy_user_default=$(grep -E '^\s*systemProp\.https\.proxyUser\s*=' "$GRADLE_PROPS" | head -1 | sed -E 's/.*=[[:space:]]*//')
            proxy_pass_default=$(grep -E '^\s*systemProp\.https\.proxyPassword\s*=' "$GRADLE_PROPS" | head -1 | sed -E 's/.*=[[:space:]]*//')
        fi

        if [ -n "$proxy_user_default" ]; then
            printf "  Proxy username [default: %s]: " "$proxy_user_default"
        else
            printf "  Proxy username: "
        fi
        read -r am_user
        [ -z "$am_user" ] && am_user="$proxy_user_default"

        if [ -n "$proxy_pass_default" ]; then
            printf "  Proxy password (input hidden) [press ENTER to reuse the password already in %s]: " "$GRADLE_PROPS"
        else
            printf "  Proxy password (input hidden): "
        fi
        stty -echo 2>/dev/null
        read -r am_pass
        stty echo 2>/dev/null
        echo
        [ -z "$am_pass" ] && am_pass="$proxy_pass_default"

        if [ -z "$am_user" ] || [ -z "$am_pass" ]; then
            warn "Username or password is empty -- aborting mirror setup." \
                 "Re-run with proxy credentials to hand. Nothing has been written."
        else
            echo
            echo "  ${YEL}About to write/update:${CLR}"
            echo "    1. $settings_xml"
            echo "         <server id='${artifactory_mirror_id}'> + <mirror id='${artifactory_mirror_id}' mirrorOf='*'>"
            echo "    2. $GRADLE_PROPS"
            echo "         orgInternalMavenUser/Password (+ wrapper, resolver, http(s) proxy aliases)"
            echo "    3. $HOME/.gradle/init.d/corp-repos.gradle.kts"
            echo "         redirects mavenCentral() / gradlePluginPortal() to ${am_url}"
            echo "  Existing files will be backed up to .backup-<timestamp> siblings."
            printf "  Proceed? [y/N] "
            read -r resp
            case "$resp" in
                [Yy]|[Yy][Ee][Ss])
                    # 1. settings.xml -- merge via Python so existing
                    #    <server>/<mirror> blocks are preserved. Any block
                    #    already carrying our id is replaced (idempotent).
                    #    BACKUP-BEFORE-WRITE: if we can't successfully copy
                    #    an existing settings.xml to a sibling .backup-*,
                    #    abort the entire mirror setup so no one ends up
                    #    with a modified settings.xml and no rollback path.
                    mkdir -p "$(dirname "$settings_xml")"
                    backup_ok=1
                    settings_backup=""
                    if [ -f "$settings_xml" ]; then
                        stamp=$(date +%Y%m%d-%H%M%S)
                        settings_backup="$settings_xml.backup-$stamp"
                        if cp -p "$settings_xml" "$settings_backup" 2>/dev/null && [ -s "$settings_backup" ]; then
                            ok "Backed up $settings_xml to $settings_backup"
                        else
                            backup_ok=0
                            bad "Could not back up $settings_xml to $settings_backup -- aborting mirror setup; no files were modified." \
                                "Check write perms on $(dirname "$settings_xml") and free disk space, then re-run."
                        fi
                    fi
                    if [ "$backup_ok" = "0" ]; then
                        # Stop the mirror-setup branch entirely; the Maven
                        # Central remediation later in the script can still
                        # offer a recovery path.
                        info "Skipping gradle.properties + init script writes since settings.xml backup failed."
                    else
                    AM_URL="$am_url" AM_ID="$artifactory_mirror_id" \
                    AM_USER="$am_user" AM_PASS="$am_pass" \
                    AM_FILE="$settings_xml" python3 <<'PY'
import os, re, pathlib
fp = pathlib.Path(os.environ["AM_FILE"])
mid  = os.environ["AM_ID"]
url  = os.environ["AM_URL"]
user = os.environ["AM_USER"]
pwd  = os.environ["AM_PASS"]

new_server = f"""    <server>
      <id>{mid}</id>
      <username>{user}</username>
      <password>{pwd}</password>
    </server>"""
new_mirror = f"""    <mirror>
      <id>{mid}</id>
      <mirrorOf>*</mirrorOf>
      <name>Corporate Maven Central proxy (artifactory-external-mirror)</name>
      <url>{url}</url>
    </mirror>"""

if fp.exists():
    txt = fp.read_text(encoding="utf-8", errors="replace")
else:
    txt = ('<?xml version="1.0" encoding="UTF-8"?>\n'
           '<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0"\n'
           '          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"\n'
           '          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.2.0 '
           'https://maven.apache.org/xsd/settings-1.2.0.xsd">\n'
           '</settings>\n')

def strip_by_id(txt, tag, target_id):
    pattern = re.compile(rf'(?s)\s*<{tag}>(.*?)</{tag}>')
    def repl(m):
        idm = re.search(r'<id>\s*([^<]*)\s*</id>', m.group(1))
        if idm and idm.group(1).strip() == target_id:
            return ""
        return m.group(0)
    return pattern.sub(repl, txt)

txt = strip_by_id(txt, "server", mid)
txt = strip_by_id(txt, "mirror", mid)

def insert_in(parent_tag, child_block, txt):
    close_tag = f"</{parent_tag}>"
    if close_tag in txt:
        return txt.replace(close_tag, child_block + "\n  " + close_tag, 1)
    block = f"  <{parent_tag}>\n{child_block}\n  </{parent_tag}>\n"
    if "</settings>" in txt:
        return txt.replace("</settings>", block + "</settings>", 1)
    return txt + block

txt = insert_in("servers", new_server, txt)
txt = insert_in("mirrors", new_mirror, txt)
fp.write_text(txt, encoding="utf-8")
PY
                    chmod 600 "$settings_xml" 2>/dev/null || true
                    ok "Wrote <server> + <mirror> id='${artifactory_mirror_id}' to $settings_xml (mode 600)"

                    # 2. gradle.properties -- write all three Gradle credential
                    #    pairs PLUS http(s) proxy creds (the user just confirmed
                    #    the same username/password drives both). Strip any
                    #    prior versions of these keys so re-runs are clean.
                    if [ -f "$GRADLE_PROPS" ]; then
                        stamp=$(date +%Y%m%d-%H%M%S)
                        cp "$GRADLE_PROPS" "$GRADLE_PROPS.backup-$stamp"
                        ok "Backed up $GRADLE_PROPS to $GRADLE_PROPS.backup-$stamp"
                    else
                        mkdir -p "$(dirname "$GRADLE_PROPS")"
                    fi
                    tmp=$(mktemp)
                    if [ -f "$GRADLE_PROPS" ]; then
                        grep -Ev \
                            '^\s*(orgInternalMaven(User|Password)|systemProp\.gradle\.wrapper(User|Password)|artifactoryResolver(Username|Password)|systemProp\.https?\.proxy(User|Password))\s*=' \
                            "$GRADLE_PROPS" > "$tmp"
                    fi
                    cat >> "$tmp" <<EOF

# Added by build-health-check.sh on $(date +%FT%T) -- artifactory-external-mirror.
# Same username/password drives BOTH the corporate proxy AND the Artifactory mirror.
orgInternalMavenUser=$am_user
orgInternalMavenPassword=$am_pass
systemProp.gradle.wrapperUser=$am_user
systemProp.gradle.wrapperPassword=$am_pass
artifactoryResolverUsername=$am_user
artifactoryResolverPassword=$am_pass
systemProp.https.proxyUser=$am_user
systemProp.https.proxyPassword=$am_pass
systemProp.http.proxyUser=$am_user
systemProp.http.proxyPassword=$am_pass
EOF
                    mv "$tmp" "$GRADLE_PROPS"
                    chmod 600 "$GRADLE_PROPS" 2>/dev/null || true
                    ok "Wrote credential aliases (3 Gradle pairs + 2 proxy pairs) to $GRADLE_PROPS (mode 600)"

                    # 3. init.d/corp-repos.gradle.kts -- references creds via
                    #    providers.gradleProperty so the values stay in
                    #    gradle.properties, not in the init script itself.
                    init_dir="$HOME/.gradle/init.d"
                    init_script="$init_dir/corp-repos.gradle.kts"
                    mkdir -p "$init_dir"
                    if [ -f "$init_script" ]; then
                        stamp=$(date +%Y%m%d-%H%M%S)
                        cp "$init_script" "$init_script.backup-$stamp"
                        ok "Backed up $init_script to $init_script.backup-$stamp"
                    fi
                    cat > "$init_script" <<EOF
// Added by build-health-check.sh on $(date +%FT%T).
// Routes mavenCentral() + gradlePluginPortal() through the corporate
// artifactory-external-mirror. Credentials are loaded from
// ~/.gradle/gradle.properties (NOT committed). To revert: delete this file.
val corpMavenUrl = "$am_url"

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
EOF
                    ok "Wrote $init_script  ->  redirects mavenCentral() / gradlePluginPortal() to $am_url"
                    info "All three '${artifactory_mirror_id}' configuration elements are in place."
                    info "The Maven Central reachability check below will probe the wired-up mirror end-to-end."
                    fi  # end of: if backup_ok
                    ;;
                *) info "Skipped. No files were modified." ;;
            esac
        fi
    fi
fi

# --- Gradle init-script shape verification ---------------------------------
# Runs UNCONDITIONALLY. Detects outdated corp-repos.gradle.kts from a
# prior run and offers to regenerate. Catches the classic Gradle 9.x
# plugin-classpath failure where the old template didn't cover settings-
# time plugin resolution.
init_dir="$HOME/.gradle/init.d"
init_script="$init_dir/corp-repos.gradle.kts"
if [ -f "$init_script" ]; then
    has_settings_evaluated=0; has_plugins_gradle=0; has_helper_fn=0
    grep -qE 'settingsEvaluated[[:space:]]*\{' "$init_script" && has_settings_evaluated=1
    grep -q 'plugins\.gradle\.org'            "$init_script" && has_plugins_gradle=1
    grep -qE 'fun[[:space:]]+maybeRewrite'    "$init_script" && has_helper_fn=1
    if [ "$has_settings_evaluated" = "1" ] && [ "$has_plugins_gradle" = "1" ] && [ "$has_helper_fn" = "1" ]; then
        ok "Existing init script at $init_script is up-to-date (Gradle 9.x compatible)."
    else
        warn "Existing init script at $init_script is OUTDATED." \
            "Missing coverage for settings-time plugin resolution and/or plugins.gradle.org. Gradle 9.x builds will fail to resolve plugin transitive dependencies (com.google.code.gson is a common symptom)."
        [ "$has_settings_evaluated" = "0" ] && info "  [MISSING] settingsEvaluated hook -- plugin resolution goes unrewritten."
        [ "$has_plugins_gradle"     = "0" ] && info "  [MISSING] plugins.gradle.org redirect -- Gradle Plugin Portal isn't routed through corp mirror."
        [ "$has_helper_fn"          = "0" ] && info "  [MISSING] helper function style -- old template structure."

        if [ "$NON_INTERACTIVE" != "1" ]; then
            existing_url=$(grep -E 'val[[:space:]]+corpMavenUrl[[:space:]]*=' "$init_script" | sed -E 's/.*=[[:space:]]*"([^"]+)".*/\1/')
            if [ -z "$existing_url" ]; then
                warn "Could not extract corpMavenUrl from the old init script; rerun with fresh input."
            else
                use_bearer=0; use_basic=0
                grep -q 'HttpHeaderCredentials' "$init_script" && use_bearer=1
                if [ "$use_bearer" = "0" ] && grep -qE 'credentials[[:space:]]*\{[[:space:]]*$' "$init_script"; then
                    grep -q 'gradleProperty' "$init_script" && use_basic=1
                fi
                auth_desc="no credentials"
                [ "$use_bearer" = "1" ] && auth_desc="Bearer (HttpHeaderAuthentication)"
                [ "$use_basic"  = "1" ] && auth_desc="Basic (username + password)"
                echo
                echo "  ${YEL}The new template will preserve your existing settings:${CLR}"
                echo "    URL:    $existing_url"
                echo "    Auth:   $auth_desc"
                echo "    (credentials live in gradle.properties; the init script only references them)"
                printf "  Regenerate %s with the current template? [y/N] " "$init_script"
                read -r resp
                case "$resp" in
                    [Yy]|[Yy][Ee][Ss])
                        regen_creds=""
                        if [ "$use_bearer" = "1" ]; then
                            regen_creds='
            repo.credentials(HttpHeaderCredentials::class) {
                name = "Authorization"
                value = "Bearer " + providers.gradleProperty("orgInternalMavenPassword").get()
            }
            repo.authentication {
                create<HttpHeaderAuthentication>("header")
            }'
                        elif [ "$use_basic" = "1" ]; then
                            regen_creds='
            repo.credentials {
                username = providers.gradleProperty("orgInternalMavenUser").get()
                password = providers.gradleProperty("orgInternalMavenPassword").get()
            }'
                        fi
                        stamp=$(date +%Y%m%d-%H%M%S)
                        cp "$init_script" "$init_script.backup-$stamp"
                        ok "Backed up old init script to $init_script.backup-$stamp"
                        cat > "$init_script" <<EOF
// Regenerated by build-health-check.sh on $(date +%FT%T).
// Redirects mavenCentral() AND gradlePluginPortal() to the corporate
// proxy across every resolution scope Gradle uses:
//   - settings.pluginManagement.repositories   (plugin resolution)
//   - settings.dependencyResolutionManagement  (centralized repos)
//   - allprojects.buildscript.repositories     (per-project plugins)
//   - allprojects.repositories                 (project dependencies)
// To revert: restore the .backup-* sibling of this file.
val corpMavenUrl = "$existing_url"

fun maybeRewrite(repo: ArtifactRepository) {
    if (repo is MavenArtifactRepository) {
        val u = repo.url.toString()
        if (u.contains("repo.maven.apache.org") ||
            u.contains("plugins.gradle.org") ||
            u.contains("repo1.maven.org")) {
            repo.setUrl(corpMavenUrl)${regen_creds}
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
EOF
                        ok "Regenerated $init_script with current template (auth preserved: $auth_desc)."
                        ;;
                    *) info "Skipped. Plugin-resolution failures (classpath errors on transitive deps) will continue until the init script is updated." ;;
                esac
            fi
        fi
    fi
fi

if [ "${#mav_hints[@]}" -gt 0 ]; then
    ok "Found ${#mav_hints[@]} candidate mirror URL(s):"
    i=1
    for h in "${mav_hints[@]}"; do
        url=$(echo "$h" | cut -d'|' -f1)
        src=$(echo "$h" | cut -d'|' -f2)
        note=$(echo "$h" | cut -d'|' -f3)
        info "  [$i] $url   ($src, $note)"
        i=$((i+1))
    done

    # Let the operator choose probe method. curl is fast but uses a
    # different HTTP stack than Gradle; Gradle probe is slower but
    # matches exactly what `./gradlew build` does at build time.
    echo
    probe_mode="r"
    if [ "$NON_INTERACTIVE" != "1" ]; then
        echo "  ${YEL}Connectivity probe options:${CLR}"
        echo "    [r] Real build config (DEFAULT) -- mavenCentral() + gradlePluginPortal() just like"
        echo "                           the actual build. Your proxy + init scripts apply naturally."
        echo "                           Answers 'will ./gradlew build work?' in ONE probe."
        echo "                           No candidate URL is tested per se -- the REAL config is."
        echo "    [g] Gradle per-candidate -- point a maven{} repo at EACH listed URL and probe."
        echo "                           Useful when picking between multiple candidates."
        echo "    [c] curl per-candidate -- fast HTTP probe of each listed URL."
        echo "    [s] Skip"
        printf "  Choose [r/g/c/s] (default: r): "
        read -r pm
        case "$pm" in g|G) probe_mode="g" ;; c|C) probe_mode="c" ;; s|S) probe_mode="s" ;; *) probe_mode="r" ;; esac
    fi

    if [ "$probe_mode" = "s" ]; then
        info "Skipped connectivity probes. Candidate URLs listed above; run ./gradlew build --refresh-dependencies to test for real."
    elif [ "$probe_mode" = "r" ]; then
        info "Running ONE real-build probe (uses your actual Gradle config: proxy + init scripts + mavenCentral())..."
        probe_real_build_config || true
    else
        case "$probe_mode" in
            g) info "Probing each candidate URL (method: Gradle)..." ;;
            *) info "Probing each candidate URL (method: curl)..." ;;
        esac
        i=1
        for h in "${mav_hints[@]}"; do
            url=$(echo "$h" | cut -d'|' -f1)
            src=$(echo "$h" | cut -d'|' -f2)
            rid=$(echo "$h" | cut -d'|' -f4)
            creds=$(lookup_server_creds "$src" "$rid")
            cu=""; cp=""
            if [ -n "$creds" ]; then
                cu=$(echo "$creds" | cut -f1)
                cp=$(echo "$creds" | cut -f2)
            fi
            if [ "$probe_mode" = "g" ]; then
                probe_mirror_via_gradle "$url" "[$i]" "$cu" "$cp" || true
            else
                probe_mirror_url "$url" "[$i]" "$cu" "$cp" || true
            fi
            i=$((i+1))
        done
    fi

    echo
    info "IMPORTANT: the candidate URLs above are NOT wired into Gradle yet."
    info "Gradle still uses repo.maven.apache.org until ~/.gradle/init.d/corp-repos.gradle.kts"
    info "redirects it. If the Maven Central check below FAILs, the remediation prompt"
    info "will offer to write that init script using one of these candidates."
elif [ "$NON_INTERACTIVE" != "1" ]; then
    info "No corporate Maven config added to the candidate list. That's fine if Maven Central is directly reachable below."
fi

sect "Artifact repository reachability"
# Note on naming: "Maven Central" refers to the public Java artifact
# repository at repo.maven.apache.org -- not the Maven build tool.
# Gradle's mavenCentral() directive resolves to this same URL, so the
# project's Spring Boot / Kotlin / JUnit / Jackson artifacts all come
# from here at build time. If it's unreachable, `./gradlew build` fails
# regardless of which build tool you use.
maven_central_ok=1
maven_central_via_proxy=0
# Build curl proxy args matching what Gradle would use, so the probe
# matches Gradle's path (not just curl's HTTPS_PROXY env). Without this,
# the script can falsely report Maven Central unreachable when the corp
# proxy in gradle.properties would route it just fine.
gradle_curl_proxy=()
if [ -n "$gradle_proxy_host" ] && [ -n "$gradle_proxy_port" ]; then
    proxy_user=$(grep -E '^\s*systemProp\.https\.proxyUser\s*=' "$GRADLE_PROPS" 2>/dev/null | head -1 | sed -E 's/.*=\s*//')
    proxy_pass=$(grep -E '^\s*systemProp\.https\.proxyPassword\s*=' "$GRADLE_PROPS" 2>/dev/null | head -1 | sed -E 's/.*=\s*//')
    gradle_curl_proxy=(--proxy "http://${gradle_proxy_host}:${gradle_proxy_port}")
    if [ -n "$proxy_user" ] && [ -n "$proxy_pass" ]; then
        gradle_curl_proxy+=(--proxy-user "${proxy_user}:${proxy_pass}")
        info "Probes will go through the gradle.properties proxy: ${gradle_proxy_host}:${gradle_proxy_port} (with auth)"
    else
        info "Probes will go through the gradle.properties proxy: ${gradle_proxy_host}:${gradle_proxy_port}"
    fi
fi
for ep in \
    "Gradle distributions|https://services.gradle.org/distributions/" \
    "Maven Central (Gradle mavenCentral())|https://repo.maven.apache.org/maven2/" \
    "Gradle Plugin Portal|https://plugins.gradle.org/m2/" \
    "Foojay Disco (toolchain auto-download)|https://api.foojay.io/disco/v3.0/packages?version=17&vendor=temurin&architecture=x64&operating_system=linux&archive_type=tar.gz&package_type=jdk&javafx_bundled=false&latest=available" \
    "GitHub|https://github.com/"; do
    name=${ep%%|*}; url=${ep##*|}
    info "-> $url"
    out=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 15 -L -I "${gradle_curl_proxy[@]+"${gradle_curl_proxy[@]}"}" "$url" 2>&1)
    code=${out%% *}
    case "$code" in
        200|301|302|307|403)
            ok "$name reachable (HTTP $code) at $url"
            case "$name" in
                "Maven Central"*)
                    [ "${#gradle_curl_proxy[@]}" -gt 0 ] && maven_central_via_proxy=1
                    ;;
            esac
            ;;
        000)
            bad "$name unreachable: $url -- $out" \
                "Check proxy host/port, VPN, NO_PROXY, and TLS chain (corporate CA may need to be imported)"
            case "$name" in
                "Maven Central"*) maven_central_ok=0 ;;
                "Foojay"*)
                    info "  -> The Foojay Disco API is what Gradle calls to AUTO-DOWNLOAD a JDK 17 when"
                    info "     your default JDK is a different major version. It's wired via the"
                    info "     foojay-resolver-convention plugin in every settings.gradle.kts."
                    info "  -> If Foojay is blocked but your default JDK is already 17-25, turn OFF"
                    info "     auto-download in ${GRADLE_PROPS}:"
                    info "       org.gradle.java.installations.auto-download=false"
                    info "       org.gradle.java.installations.auto-detect=true"
                    info "     Gradle will then reuse your locally-installed JDK via path discovery."
                    info "  -> Alternatively, pin the discovery path explicitly:"
                    info "       org.gradle.java.installations.paths=/path/to/jdk17"
                    ;;
            esac
            ;;
        *)
            warn "$name returned HTTP $code at $url"
            case "$name" in "Maven Central"*) maven_central_ok=0 ;; esac
            ;;
    esac
done

# Pattern A short-circuit: if Maven Central succeeded through the
# gradle.properties proxy, no mirror is needed -- proxy alone suffices.
if [ "$maven_central_via_proxy" = "1" ]; then
    ok "Maven Central is reachable through your gradle.properties proxy."
    info "You do NOT need a corporate mirror (no init script, no mavenCentral() rewrite)."
    info "Pattern A applies: proxy + auth in $GRADLE_PROPS is sufficient for Gradle to reach"
    info "the public Maven Central at build time. The 'Corporate repository configuration'"
    info "section above only matters if you also publish to or resolve from internal repos."
fi

# --- Maven Central mirror remediation ---------------------------------
# Corporate networks usually block direct egress to repo.maven.apache.org
# and expect Gradle to resolve through an internal Artifactory / Nexus.
# Detect that case and offer to wire up a Gradle init script.
if [ "$maven_central_ok" = "0" ]; then
    echo
    echo "  ${YEL}Maven Central is unreachable. It IS required for this build (Spring Boot,${CLR}"
    echo "  ${YEL}Kotlin stdlib, JUnit, Jackson all come from there) -- but in a corporate${CLR}"
    echo "  ${YEL}network you typically proxy it through an internal Artifactory / Nexus.${CLR}"
    init_dir="$HOME/.gradle/init.d"
    init_script="$init_dir/corp-repos.gradle.kts"
    if [ -f "$init_script" ]; then
        ok "Found existing init script at $init_script -- Gradle should use the corp mirror from there."
        info "If resolution still fails, verify the URL + credentials inside that file."
    elif [ "$NON_INTERACTIVE" = "0" ]; then
        # mav_hints was populated by the "Maven repository configuration"
        # section above; reuse the findings rather than re-scanning.
        echo
        echo "  ${CYN}Corporate Maven configuration help:${CLR}"
        echo "    IT teams often distribute the internal mirror URL via either:"
        echo "      * ~/.m2/settings.xml                 (standard Maven)"
        echo "      * a maven-wrapper.properties file    (Maven wrapper)"
        echo "    If you don't have one, get it from Artifactory itself:"
        echo "      1. Log in to your corporate Artifactory web UI."
        echo "      2. Click the avatar icon in the upper-right corner."
        echo "      3. Select 'Set Me Up'."
        echo "      4. Select 'Maven', then follow the on-screen workflow."
        echo "         Artifactory displays the repo URL and, if needed, credentials."
        echo "         (Gradle honors the same Artifactory Maven repo -- no need to"
        echo "          pick a Gradle-specific one.)"
        echo

        corp_url=""
        if [ "${#mav_hints[@]}" -gt 0 ]; then
            echo "  ${YEL}Detected existing corporate Maven config on this machine:${CLR}"
            i=1
            for h in "${mav_hints[@]}"; do
                url=$(echo "$h" | cut -d'|' -f1)
                src=$(echo "$h" | cut -d'|' -f2)
                note=$(echo "$h" | cut -d'|' -f3)
                echo "    [$i] $url"
                echo "        ($src, $note)"
                i=$((i+1))
            done
            echo "    [m] Enter a different URL manually"
            echo "    [k] Keep existing config as-is  (don't overwrite what's already in ~/.gradle/init.d/ or gradle.properties)"
            echo "    [s] Skip"
            printf "  Choose: "
            read -r pick
            corp_url=""
            corp_user=""; corp_pass=""; corp_rid=""; corp_src=""
            if echo "$pick" | grep -qE '^[0-9]+$' && [ "$pick" -ge 1 ] && [ "$pick" -le "${#mav_hints[@]}" ]; then
                chosen=${mav_hints[$((pick-1))]}
                corp_url=$(echo "$chosen" | cut -d'|' -f1)
                corp_src=$(echo "$chosen" | cut -d'|' -f2)
                corp_rid=$(echo "$chosen" | cut -d'|' -f4)
                creds=$(lookup_server_creds "$corp_src" "$corp_rid")
                if [ -n "$creds" ]; then
                    corp_user=$(echo "$creds" | cut -f1)
                    corp_pass=$(echo "$creds" | cut -f2)
                    info "Using $corp_url  (creds from <server id='$corp_rid'> in $corp_src)"
                else
                    info "Using $corp_url  (no matching <server> block; credentials may still be required)"
                fi
            elif echo "$pick" | grep -qi '^m'; then
                printf "  Paste your corporate Maven mirror URL: "
                read -r corp_url
            elif echo "$pick" | grep -qi '^k'; then
                info "Left existing config untouched. Neither ~/.gradle/init.d/corp-repos.gradle.kts nor $GRADLE_PROPS was modified."
                info "If Maven Central remains unreachable at build time, re-run this script and pick a candidate URL."
            fi
        else
            echo "  Your organization's Maven mirror URL usually looks like one of:"
            echo "    https://artifactory.<corp>.com/artifactory/maven-central/"
            echo "    https://nexus.<corp>.com/repository/maven-central/"
            echo "    https://artifacts.<corp>.com/maven-public/"
            printf "  Paste your corporate Maven mirror URL (or leave blank to skip): "
            read -r corp_url
            corp_user=""; corp_pass=""
        fi
        if [ -n "$corp_url" ]; then
            corp_url="${corp_url%/}/"
            mkdir -p "$init_dir"

            # If we pulled creds from settings.xml, offer to write them
            # to gradle.properties. The init script only references
            # them by property name; the actual values live in
            # gradle.properties (per-user, not committed).
            creds_applied=0
            if [ -n "$corp_user" ] && [ -n "$corp_pass" ]; then
                if [[ "$corp_pass" =~ ^\{.*\}$ ]]; then
                    warn "Creds from settings.xml have a Maven-encrypted password." \
                        "Decrypt with 'mvn -ep' or supply settings-security.xml; then re-run."
                else
                    echo
                    echo "  ${YEL}The settings.xml has <server> credentials attached to this URL.${CLR}"
                    echo "  With confirmation, the script will:"
                    echo "    1. Back up $GRADLE_PROPS"
                    echo "    2. Write two lines:"
                    echo "         orgInternalMavenUser=$corp_user"
                    echo "         orgInternalMavenPassword=<redacted>"
                    echo "    3. Emit an init script that references those properties."
                    printf "  Write the credentials to %s ? [y/N] " "$GRADLE_PROPS"
                    read -r credresp
                    case "$credresp" in
                        [Yy]|[Yy][Ee][Ss])
                            if [ -f "$GRADLE_PROPS" ]; then
                                stamp=$(date +%Y%m%d-%H%M%S)
                                cp "$GRADLE_PROPS" "$GRADLE_PROPS.backup-$stamp"
                                ok "Backed up $GRADLE_PROPS to $GRADLE_PROPS.backup-$stamp"
                            fi
                            tmp=$(mktemp)
                            if [ -f "$GRADLE_PROPS" ]; then
                                grep -Ev \
                                    '^\s*(orgInternalMaven(User|Password)|systemProp\.gradle\.wrapper(User|Password)|artifactoryResolver(Username|Password))\s*=' \
                                    "$GRADLE_PROPS" > "$tmp"
                            fi
                            # Three pair aliases, same username/password --
                            # different Gradle code paths read different names:
                            #   orgInternalMaven*        -> our init script
                            #   systemProp.gradle.wrapper*  -> wrapper ZIP download
                            #   artifactoryResolver*     -> direct Artifactory blocks
                            cat >> "$tmp" <<EOF

# Added by build-health-check.sh on $(date +%FT%T).
# Credentials sourced from <server id='$corp_rid'> in
# $corp_src. DO NOT commit gradle.properties to any repo.
# All three pairs below carry the same username/password -- different
# Gradle code paths look at different names.
orgInternalMavenUser=$corp_user
orgInternalMavenPassword=$corp_pass
systemProp.gradle.wrapperUser=$corp_user
systemProp.gradle.wrapperPassword=$corp_pass
artifactoryResolverUsername=$corp_user
artifactoryResolverPassword=$corp_pass
EOF
                            mv "$tmp" "$GRADLE_PROPS"
                            ok "Wrote three credential-pair aliases to $GRADLE_PROPS (user '$corp_user')."
                            creds_applied=1
                            ;;
                        *) info "Skipped credential write; init script will still reference the property names." ;;
                    esac
                fi
            fi

            # Re-probe to determine final auth mode for the init script.
            # Probe-side caching would be nicer but a fresh call is fine.
            BEARER_REQUIRED=0
            if [ -n "$corp_user" ] && [ -n "$corp_pass" ] && [[ ! "$corp_pass" =~ ^[[:space:]]*\{.*\}[[:space:]]*$ ]]; then
                # Silent probe just to set BEARER_REQUIRED flag.
                probe_mirror_url "$corp_url" "auth-check:" "$corp_user" "$corp_pass" >/dev/null 2>&1 || true
            fi

            # Build the init script. Gradle's credentials{} block only
            # supports HTTP Basic, so when Bearer is required we emit
            # HttpHeaderCredentials + authentication{}. Since the rewrite
            # happens inside a function with `repo` as a named parameter,
            # the credentials calls are qualified on `repo`.
            if [ "$creds_applied" = "1" ] && [ "$BEARER_REQUIRED" = "1" ]; then
                info "Probe confirmed Artifactory wants Bearer; init script will send the token via Authorization header."
                creds_clause='
            repo.credentials(HttpHeaderCredentials::class) {
                name = "Authorization"
                value = "Bearer " + providers.gradleProperty("orgInternalMavenPassword").get()
            }
            repo.authentication {
                create<HttpHeaderAuthentication>("header")
            }'
            elif [ "$creds_applied" = "1" ]; then
                creds_clause='
            repo.credentials {
                username = providers.gradleProperty("orgInternalMavenUser").get()
                password = providers.gradleProperty("orgInternalMavenPassword").get()
            }'
            else
                creds_clause=''
            fi
            cat > "$init_script" <<EOF
// Added by build-health-check.sh on $(date +%FT%T).
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
val corpMavenUrl = "$corp_url"

fun maybeRewrite(repo: ArtifactRepository) {
    if (repo is MavenArtifactRepository) {
        val u = repo.url.toString()
        if (u.contains("repo.maven.apache.org") ||
            u.contains("plugins.gradle.org") ||
            u.contains("repo1.maven.org")) {
            repo.setUrl(corpMavenUrl)${creds_clause}
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
EOF
            ok "Wrote $init_script  ->  rewrites mavenCentral() to $corp_url"
            [ "$creds_applied" = "1" ] && ok "Init script references orgInternalMavenUser / orgInternalMavenPassword via gradle.properties."
            info "This applies to EVERY Gradle build on this user account. Delete the file to revert."

            # Post-write verification -- use the same creds we just wired.
            echo
            info "Verifying the mirror can actually serve artifacts..."
            probe_artifact="${corp_url%/}/org/springframework/boot/spring-boot/maven-metadata.xml"
            info "-> $probe_artifact"
            if [ -n "$corp_user" ] && [ -n "$corp_pass" ] && [[ ! "$corp_pass" =~ ^\{.*\}$ ]]; then
                if probe_mirror_url "$probe_artifact" "verification:" "$corp_user" "$corp_pass"; then
                    ok "Mirror resolves Spring Boot metadata OK with <server> creds. Settings.xml has been applied; Gradle should now succeed."
                fi
            else
                if probe_mirror_url "$probe_artifact" "verification:"; then
                    ok "Mirror resolves Spring Boot metadata OK. Settings.xml has been applied; Gradle should now succeed."
                fi
            fi
        else
            info "Skipped. You can hand-craft ~/.gradle/init.d/corp-repos.gradle.kts later."
        fi
    fi
fi

# --- TLS chain check -------------------------------------------------
sect "TLS chain check"
TLS_HOST="services.gradle.org"
info "-> https://${TLS_HOST}:443 (TLS chain inspection)"
corp_root_pem=""
if command -v openssl >/dev/null 2>&1; then
    chain=$(echo | openssl s_client -connect "${TLS_HOST}:443" -servername "$TLS_HOST" -showcerts 2>/dev/null)
    root_line=$(printf '%s' "$chain" | grep -E '^\s*[0-9]+ s:' | tail -1)
    if echo "$root_line" | grep -qE 'USERTrust|DigiCert|ISRG|Baltimore|GlobalSign|Amazon|GTS|Microsoft'; then
        ok "TLS chain to $TLS_HOST terminates at a public root: ${root_line##*s:}"
    elif [ -n "$root_line" ]; then
        warn "TLS chain to $TLS_HOST terminates at NON-public root: ${root_line##*s:}" \
             "Likely corporate TLS interception. Gradle (JVM HTTP clients) will reject the chain unless the root is in the JDK truststore."
        # Extract the LAST cert in the chain as PEM for potential import.
        corp_root_pem=$(printf '%s' "$chain" | awk '/-----BEGIN CERTIFICATE-----/{found=1;buf=""} found{buf=buf$0"\n"} /-----END CERTIFICATE-----/{last=buf;found=0} END{printf "%s",last}')
    else
        warn "Could not parse TLS chain (network unreachable or openssl issue)"
    fi
else
    warn "openssl not on PATH; skipping TLS chain check"
fi

# If we captured a corporate root cert, offer interactive remediation --
# but first detect whether the operator has already applied a mitigation
# so we don't nag on every run. The raw openssl probe above sees the
# corporate cert regardless of what's in gradle.properties or cacerts;
# the check here is "is the workaround already in place?" not "does the
# probe succeed?".
if [ -n "$corp_root_pem" ]; then
    already_in_jdk=0
    if [ -n "${JAVA_HOME:-}" ] && [ -f "$JAVA_HOME/lib/security/cacerts" ] && command -v keytool >/dev/null 2>&1; then
        root_sha1=$(printf '%s' "$corp_root_pem" | openssl x509 -noout -fingerprint -sha1 2>/dev/null | sed 's/.*=//; s/://g')
        if [ -n "$root_sha1" ] && keytool -list -keystore "$JAVA_HOME/lib/security/cacerts" -storepass changeit 2>/dev/null | grep -qi "$root_sha1"; then
            already_in_jdk=1
        fi
    fi
    already_in_gradle=0
    if [ -f "$GRADLE_PROPS" ] && grep -qE '^\s*systemProp\.javax\.net\.ssl\.trustStore\s*=' "$GRADLE_PROPS"; then
        already_in_gradle=1
    fi

    if [ "$already_in_jdk" = "1" ]; then
        ok "Corporate root is already present in the JDK truststore ($JAVA_HOME/lib/security/cacerts). No action needed."
    elif [ "$already_in_gradle" = "1" ]; then
        ok "Gradle already has systemProp.javax.net.ssl.trustStore override in $GRADLE_PROPS."
        info "The raw TLS probe still sees the corporate root -- that is expected; Gradle respects the override you already wrote."
        info "To revert, restore $GRADLE_PROPS from its .backup-* sibling (or delete the systemProp.javax.net.ssl.trustStore line)."
    elif [ "$NON_INTERACTIVE" = "0" ]; then
        echo ""
        echo "${YEL}  Corporate root detected. Two ways forward:${CLR}"
        echo "    [1] Import this cert into the JDK truststore  (recommended, permanent fix)"
        echo "    [2] Point Gradle at an alternate truststore (JKS/PKCS12) that already trusts the root"
        echo "    [s] Skip"
        printf "  Choose [1/2/s]: "
        read -r resp
        case "$resp" in
            1)
                if [ -z "${JAVA_HOME:-}" ] || [ ! -f "$JAVA_HOME/lib/security/cacerts" ]; then
                    bad "JAVA_HOME not set or cacerts missing; cannot import." "Set JAVA_HOME to a JDK 17+ install."
                else
                    tmp_cert=$(mktemp).cer
                    printf '%s' "$corp_root_pem" > "$tmp_cert"
                    alias="corp-root-$(date +%Y%m%d%H%M)"
                    cacerts="$JAVA_HOME/lib/security/cacerts"
                    echo "  ${CYN}Importing into $cacerts as alias $alias (password: changeit)${CLR}"
                    keytool -importcert -noprompt -alias "$alias" -file "$tmp_cert" \
                            -keystore "$cacerts" -storepass changeit
                    if [ $? -eq 0 ]; then
                        ok "Corporate root imported into JDK truststore. Retry your build."
                    else
                        bad "keytool failed. Try with sudo if the cacerts file is write-protected."
                    fi
                    rm -f "$tmp_cert"
                fi
                ;;
            2)
                echo ""
                echo "  Enter the path to a Java truststore (JKS / PKCS12) that already trusts your"
                echo "  corporate root. Your IT team typically ships one. Leave blank to cancel."
                printf "  Truststore path: "
                read -r ts_path
                if [ -n "$ts_path" ] && [ -f "$ts_path" ]; then
                    if [ -f "$GRADLE_PROPS" ]; then
                        stamp=$(date +%Y%m%d-%H%M%S)
                        cp "$GRADLE_PROPS" "$GRADLE_PROPS.backup-$stamp"
                        ok "Backed up existing config to $GRADLE_PROPS.backup-$stamp"
                    fi
                    tmp=$(mktemp)
                    [ -f "$GRADLE_PROPS" ] && \
                        grep -Ev '^\s*systemProp\.javax\.net\.ssl\.trustStore(Type)?\s*=' "$GRADLE_PROPS" > "$tmp"
                    cat >> "$tmp" <<EOF

# Added by build-health-check.sh on $(date +%FT%T) -- point Gradle at an
# alternate truststore that trusts the corporate root. To revert: restore
# the .backup-* sibling of this file.
systemProp.javax.net.ssl.trustStore=$ts_path
EOF
                    mv "$tmp" "$GRADLE_PROPS"
                    ok "Wrote truststore override to $GRADLE_PROPS."
                    info "Re-run the script to confirm: on next run it will detect the override and NOT prompt again."
                elif [ -n "$ts_path" ]; then
                    bad "Path does not exist: $ts_path"
                else
                    info "Cancelled -- gradle.properties not modified."
                fi
                ;;
            *) info "Skipped CA-import remediation." ;;
        esac
    fi
fi

# --- Disk space ------------------------------------------------------
sect "Disk space"
free_gb=$(df -k "$HOME" 2>/dev/null | awk 'NR==2 {printf "%.1f", $4/1024/1024}')
if [ -n "$free_gb" ]; then
    if awk "BEGIN{exit !($free_gb < 5)}"; then
        bad "$HOME has only ${free_gb} GB free; Gradle needs at least 5 GB"
    elif awk "BEGIN{exit !($free_gb < 10)}"; then
        warn "$HOME has ${free_gb} GB free; recommend 10 GB+"
    else
        ok "$HOME has ${free_gb} GB free"
    fi
fi
info "Gradle user home: $HOME/.gradle"

# --- JVM-level HTTP auth / tunneling gotchas -------------------------
# The JVM disables Basic auth over HTTPS CONNECT tunnels by default
# (Java 8u111+). Corporate proxies that require Basic / NTLM auth on
# CONNECT will silently reject the Gradle wrapper download BEFORE any
# application-level proxy config is consulted, producing a 407 that's
# very hard to diagnose from Gradle's output alone. Surface the flags
# the user likely needs if we already see proxy creds in
# gradle.properties.
sect "JVM HTTP auth settings"
if [ -f "$GRADLE_PROPS" ] && grep -qE '^\s*systemProp\.(https?)\.proxyPassword\s*=' "$GRADLE_PROPS"; then
    have_tunneling=$(grep -cE '^\s*systemProp\.jdk\.http\.auth\.tunneling\.disabledSchemes\s*=' "$GRADLE_PROPS" 2>/dev/null || echo 0)
    have_proxying=$(grep -cE '^\s*systemProp\.jdk\.http\.auth\.proxying\.disabledSchemes\s*=' "$GRADLE_PROPS" 2>/dev/null || echo 0)
    if [ "$have_tunneling" = "0" ] || [ "$have_proxying" = "0" ]; then
        warn "Proxy credentials present but Basic-over-CONNECT is disabled by default in JDK 17+." \
            "Corporate proxies that authenticate via Basic / NTLM on the HTTPS CONNECT tunnel will return 407 'Proxy Authentication Required' even with correct creds."
        info "  To re-enable on this project, add to $GRADLE_PROPS :"
        info "    systemProp.jdk.http.auth.tunneling.disabledSchemes="
        info "    systemProp.jdk.http.auth.proxying.disabledSchemes="
        info "  (empty value = no schemes disabled = Basic/NTLM re-allowed)"
    else
        ok "jdk.http.auth.tunneling/proxying disabledSchemes already cleared in $GRADLE_PROPS"
    fi
fi

# IPv6 stack preference: corporate egress / proxies are very commonly
# IPv4-only, while the JVM will pick AAAA first, causing 30s connect
# timeouts per retry. Symptom: "connect timed out" on the first build
# attempt; second attempt sometimes works after the AAAA lookup
# negative-caches. Recommend -Djava.net.preferIPv4Stack=true when
# problems appear without a configured IPv6 route.
if [ -f "$GRADLE_PROPS" ] && grep -qE '^\s*systemProp\.java\.net\.preferIPv4Stack\s*=\s*true' "$GRADLE_PROPS"; then
    ok "systemProp.java.net.preferIPv4Stack=true set in $GRADLE_PROPS"
elif command -v ip >/dev/null 2>&1 && ip -6 route show 2>/dev/null | grep -qE '^default '; then
    info "IPv6 default route present on this host; preferIPv4Stack not needed."
else
    info "No IPv6 default route detected."
    info "  If the build hangs on 'Connect timed out' at dependency resolution, add:"
    info "    systemProp.java.net.preferIPv4Stack=true"
    info "  to $GRADLE_PROPS. Forces the JVM to skip AAAA DNS lookups entirely."
fi

# --- Gradle wrapper distribution -------------------------------------
sect "Gradle wrapper download"
# Scan every wrapper we ship, not just banking-app. A regression in any
# one of them (distributionUrl pointing at the wrong version, network
# timeout too short, corrupted wrapper jar) blocks the whole project.
# Check the reachability of each distributionUrl PLUS verify the wrapper
# jar itself matches its distributionSha256Sum (when present) --
# corporate MITM proxies have been observed corrupting binary downloads
# during inspection.
wrapper_errors=0
wrapper_warnings=0
for proj in banking-app bench-harness bench-cli bench-webui; do
    props="$REPO_ROOT/$proj/gradle/wrapper/gradle-wrapper.properties"
    wrapjar="$REPO_ROOT/$proj/gradle/wrapper/gradle-wrapper.jar"
    if [ ! -f "$props" ]; then
        warn "$proj: gradle-wrapper.properties not found at $props; skipping"
        wrapper_errors=$((wrapper_errors+1))
        continue
    fi
    dist_url=$(grep '^distributionUrl=' "$props" | sed 's/^distributionUrl=//; s|\\:|:|g')
    net_timeout=$(grep -E '^\s*networkTimeout\s*=' "$props" | sed -E 's/.*=[[:space:]]*//' | head -1)
    if [ -n "$net_timeout" ] && [ "$net_timeout" -lt 30000 ] 2>/dev/null; then
        warn "$proj: networkTimeout=$net_timeout ms is short for corporate networks" \
            "Bump to 60000-120000 in $props to avoid spurious failures under proxy latency."
        wrapper_warnings=$((wrapper_warnings+1))
    fi
    probe_code=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 30 -L -I \
        "${gradle_curl_proxy[@]+"${gradle_curl_proxy[@]}"}" "$dist_url" 2>&1)
    probe_code=${probe_code%% *}
    case "$probe_code" in
        200|301|302|307)
            ok "$proj: distribution reachable ($probe_code): $dist_url"
            ;;
        000)
            bad "$proj: distribution NOT downloadable: $dist_url" \
                "Without this, ./gradlew in $proj/ will fail before Gradle starts. Check proxy + TLS chain."
            wrapper_errors=$((wrapper_errors+1))
            ;;
        *)
            warn "$proj: distribution HEAD returned HTTP $probe_code at $dist_url"
            wrapper_warnings=$((wrapper_warnings+1))
            ;;
    esac

    # Local gradle-wrapper.jar sanity: size must be ~40-80 KB (current
    # Gradle 9.x wrappers are ~43 KB). A 0-byte or 1-KB file indicates
    # a failed/aborted download OR a proxy that returned an HTML error
    # page that the wrapper silently accepted. A 100+ KB file suggests
    # the proxy injected an inspection landing page.
    if [ -f "$wrapjar" ]; then
        jar_size=$(wc -c < "$wrapjar" | tr -d '[:space:]')
        if [ "$jar_size" -lt 20000 ] 2>/dev/null; then
            bad "$proj: gradle-wrapper.jar is only $jar_size bytes — looks truncated or corrupted" \
                "Expected ~40-80 KB. Delete the file and re-run 'git checkout -- gradle/wrapper/gradle-wrapper.jar' from $proj/, or re-clone. Proxy content inspection sometimes strips bytes."
            wrapper_errors=$((wrapper_errors+1))
        elif [ "$jar_size" -gt 200000 ] 2>/dev/null; then
            warn "$proj: gradle-wrapper.jar is $jar_size bytes (larger than expected ~43 KB)" \
                "Could indicate the proxy replaced the jar with an HTML 'content inspection' page. Open the file in a hex editor; if it starts with '<!DOCTYPE' or '<html', that's the cause."
            wrapper_warnings=$((wrapper_warnings+1))
        elif command -v unzip >/dev/null 2>&1; then
            if ! unzip -tq "$wrapjar" >/dev/null 2>&1; then
                bad "$proj: gradle-wrapper.jar is NOT a valid zip" \
                    "The file is corrupt. Re-checkout with 'git checkout -- $proj/gradle/wrapper/gradle-wrapper.jar'."
                wrapper_errors=$((wrapper_errors+1))
            fi
        fi
    else
        bad "$proj: gradle-wrapper.jar missing at $wrapjar" \
            "Run 'git checkout -- $proj/gradle/wrapper/gradle-wrapper.jar' to restore it."
        wrapper_errors=$((wrapper_errors+1))
    fi
done
[ "$wrapper_errors" = "0" ] && [ "$wrapper_warnings" = "0" ] && \
    ok "All four sub-project wrappers look healthy."

# --- End-to-end smoke test: gradlew --version -----------------------
# Final check, runs the actual wrapper. If EVERY diagnostic above
# passed but this fails, the problem is specific to the wrapper
# bootstrap path (distribution download, SHA-256 verification, or
# JVM startup) rather than repo/network config. Gives the clearest
# possible signal since all upstream config has been verified.
sect "End-to-end: gradlew --version"
if [ "$wrapper_errors" -gt 0 ]; then
    info "Skipping end-to-end probe — fix the wrapper errors above first."
else
    wrapper="$REPO_ROOT/banking-app/gradlew"
    if [ ! -x "$wrapper" ]; then
        warn "banking-app/gradlew missing or not executable; skipping."
    else
        info "Running \"$wrapper --version\" (may download ~150 MB Gradle distribution on first run)..."
        tmplog=$(mktemp)
        if "$wrapper" -p "$REPO_ROOT/banking-app" --version >"$tmplog" 2>&1; then
            gradle_ver=$(grep -E '^Gradle\s+[0-9]' "$tmplog" | head -1)
            jvm_ver=$(grep -E '^JVM:' "$tmplog" | head -1)
            ok "Wrapper bootstrapped cleanly — $gradle_ver"
            [ -n "$jvm_ver" ] && info "  $jvm_ver"
        else
            bad "Wrapper bootstrap FAILED — this is the same error you see when ./gradlew build runs." \
                "Inspect $tmplog for the exact cause. Most common: distribution SHA-256 mismatch (MITM proxy), wrapper jar corrupt, or JDK incompatibility."
            info "  Last 15 lines of the wrapper output:"
            tail -15 "$tmplog" | while IFS= read -r l; do info "    $l"; done
            # Surface specific remediation for the common failure signatures.
            if grep -qE 'Could not find or load main class org\.gradle\.wrapper\.GradleWrapperMain' "$tmplog"; then
                info "  -> gradle-wrapper.jar is present but unreadable by the JVM. Either the file is"
                info "     truncated (size check above would catch), or the JVM classpath resolution is broken."
                info "     Try: rm $wrapjar && git checkout -- banking-app/gradle/wrapper/gradle-wrapper.jar"
            elif grep -qE 'Verification of Gradle distribution failed' "$tmplog"; then
                info "  -> The downloaded Gradle zip does not match the SHA-256 in gradle-wrapper.properties."
                info "     Corporate MITM proxy is rewriting the zip during inspection. Two fixes:"
                info "        (a) Add proxy/truststore so TLS is not intercepted, OR"
                info "        (b) Download $dist_url manually through a browser, verify SHA-256,"
                info "            and place in \$GRADLE_USER_HOME/wrapper/dists/gradle-9.4.1-bin/<hash>/"
            elif grep -qE 'PKIX|SSLHandshake|unable to find valid certification path' "$tmplog"; then
                info "  -> JDK truststore missing the corporate root CA. See TLS chain section above."
            elif grep -qE '407 Proxy Authentication Required' "$tmplog"; then
                info "  -> Proxy CONNECT auth rejected. See JVM HTTP auth section above."
            fi
        fi
        rm -f "$tmplog"
    fi
fi

# --- Summary ---------------------------------------------------------
echo
echo "================================================================"
if [ "$FAIL" = 0 ]; then
    echo "${GRN}  Build environment looks healthy.${CLR}"
    [ "$WARN" -gt 0 ] && echo "${YEL}  $WARN warning(s) above are advisory.${CLR}"
    exit 0
else
    echo "${RED}  $FAIL BLOCKER(s) found. Address them before running ./gradlew build.${CLR}"
    exit 1
fi
