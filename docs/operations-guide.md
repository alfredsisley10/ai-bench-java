# ai-bench operations guide

A short reference for operators of the WebUI — how to log in, drive the
demo banking app, and recover from common startup issues.

## Logging in

The WebUI does **not** ship its own login form. Authentication is
delegated to an upstream identity proxy (oauth2-proxy, Apache mod_auth,
the corporate SSO gateway, etc.) that sets one of these request
headers on every forwarded request:

| Header             | Meaning                              |
| ------------------ | ------------------------------------ |
| `X-Forwarded-Email`| Authenticated user's email address   |
| `X-Auth-Email`     | (Alternate header name, same meaning)|

Roles are derived from email + server-side configuration:

| Role     | Granted when                                                 | Capabilities                                   |
| -------- | ------------------------------------------------------------ | ---------------------------------------------- |
| Manager  | Email is in the `aibench.admin-users` allowlist              | Full read + write (start/stop, run benchmarks) |
| Viewer   | Authenticated but not in the allowlist; `enable-read-only=true` | Read-only browsing of `/demo`, `/results`, etc.|
| Anonymous| Not authenticated and read-only is disabled                  | 403 on every page                               |

### Local development (no auth)

When the WebUI is started with `aibench.auth-enabled=false` (the
default in `application.yaml` for local runs), every request is
treated as **Manager** automatically — there is nothing to log in to.
This is the mode the WebUI runs in when launched from your laptop via
`./gradlew bootRun`.

### Adding a Manager email

Edit `~/.ai-bench/bench-config.yaml` (or the environment-specific
overlay):

```yaml
webui:
  port: 7777
  adminUsers:
    - alice@example.com
    - bob@example.com
  enableReadOnlyAccess: true
```

Restart the WebUI. The new emails take effect immediately.

### Logging out

Logout is whatever your upstream identity proxy provides — the WebUI
itself has no `/logout` endpoint. For oauth2-proxy this is typically
`/oauth2/sign_out`.

## Driving the demo banking app

The "App controls" panel on `/demo` is the operator surface for the
demo banking app (Omnibank). It boots an embedded Spring Boot process
on port **8080** in a detached JVM owned by the WebUI.

| Button             | What it does                                                                                          |
| ------------------ | ----------------------------------------------------------------------------------------------------- |
| **Start banking app** | Runs `./gradlew :app-bootstrap:bootRun` in `banking-app/`. Output streams to `banking-app/tmp/bootRun.log`. |
| **Stop**           | SIGTERMs the bootRun process; if that fails, `lsof` + `kill -9` anything listening on :8080.          |
| **Open banking app** | Opens http://localhost:8080 — the customer portal API root.                                          |
| **Health check**   | Hits `/actuator/health` and shows the JSON.                                                           |
| **View log**       | Tails `banking-app/tmp/bootRun.log`.                                                                  |

Status badges:

- **STOPPED** — no process, no listener on :8080.
- **STARTING** — process alive, health check still failing. Auto-refreshes every 3s.
- **RUNNING** — process alive, `/actuator/health` returns 200.
- **ERROR** — process exited non-zero. Check the log.

## Recording AppMaps

The `/demo/appmap` page lets a Manager kick off an AppMap recording
against the demo banking-app and browse the resulting traces.

- **Record from tests** runs `./gradlew test` with
  `ORG_GRADLE_PROJECT_appmap_enabled=true`. Traces land under
  `banking-app/**/tmp/appmap/*.appmap.json` and appear in the list when
  the gradle run completes (reload the page).
- **Module / test filter** narrow the recording to a specific gradle
  module or test class, useful for fast iteration.
- The viewer renders a server-side call tree with SQL and HTTP events
  highlighted. Click a node to expand it.

## Admin caches (precompute these once before running benchmarks)

Two operator-facing caches feed the context-provider machinery. Both
take a one-time walk-away cost; once populated, benchmark runs read
from them instantly.

### `/admin/appmap-traces` — real AppMap recordings per module

When `appmapMode != OFF`, benchmarks ship runtime traces alongside
the source. Without real recordings the harness ships synthetic
JSON stubs that parse but carry no useful runtime info — the page
lets you generate the real ones per-module via
`./gradlew :MODULE:cleanTest :MODULE:test -Pappmap_enabled=true
--no-configuration-cache --no-build-cache`. (All four flags are
required; omitting any causes gradle to silently skip the test
task and exit SUCCESSFUL with zero new traces.)

- **Generate traces for all uncovered modules** queues every
  hand-written module that currently has zero traces. Single-thread
  queue (no point parallelizing — gradle daemons thrash). 30s-5min
  per module across the ~14 hand-written modules ≈ 30-60min total.
  Pure local CPU; doesn't touch the bridge.
- Generated-* modules (regional / channels / brands / swift / nacha)
  are intentionally excluded — ~970 auto-generated tests dominate
  the run time without adding benchmark value.

### `/admin/navie` — Layer-C Navie context cache

When `contextProvider = appmap-navie`, the harness reads from a
per-bug cache populated by running the local `appmap navie` CLI
against each bug's problem statement. Without a cached entry, the
provider falls back to Oracle (`bug.filesTouched`) and the audit-
page rationale notes the miss.

- **Precompute all uncached bugs** queues every bug whose cache
  entry is absent for the current break commit. Single-thread queue
  (Navie monopolizes the bridge mutex regardless). ~15-30min per
  bug × 12 bugs ≈ 4-6 hours through the local Copilot bridge —
  start it before stepping away.
- Cache files live at `~/.ai-bench/navie-cache/<bug-id>-<sha>.json`
  and survive bench-webui restarts. Stale entries (CLI version
  change, model change) aren't auto-invalidated — re-precompute
  per-bug to refresh.
- The CLI is auto-located at `~/.appmap/bin/appmap` (matches the
  JetBrains AppMap plugin's install path); set `$APPMAP_CLI` to
  override. Wired to the Copilot bridge via `OPENAI_BASE_URL` —
  no additional auth needed.

## Troubleshooting

### Banking app shows ERROR right after Start

Check `banking-app/tmp/bootRun.log` (also accessible via the **View
log** link). Common causes:

- **`No default constructor found`** for a Spring bean — the bean has
  multiple constructors and none are marked `@Autowired`. Add the
  annotation to the constructor Spring should use.
- **`Cannot subclass final class …`** — a `@Service`/`@Repository`
  class is `final`, blocking Spring's CGLIB proxy. Drop the `final`
  modifier.
- **Port 8080 already in use** — something else is bound. Run
  `lsof -i :8080` to find the offender; the **Stop** button will
  attempt to kill it for you.

### WebUI shows "Read-only access" 403

You authenticated but your email is not in `aibench.admin-users`. Add
it to the config (see "Adding a Manager email" above) and restart.

### Gradle daemon hangs after a kill

The detached bootRun JVM may leave a Gradle daemon orphaned. Clean it
up with `./gradlew --stop` from the `banking-app/` directory.

## Where to look next

- [`docs/architecture.md`](architecture.md) — module map and harness component diagram
- [`docs/appmap-integration.md`](appmap-integration.md) — how AppMap traces feed solvers
- [`docs/eval-protocol.md`](eval-protocol.md) — how runs are scored
- [`docs/llm-integrations.md`](llm-integrations.md) — Copilot + corporate OpenAI adapters
