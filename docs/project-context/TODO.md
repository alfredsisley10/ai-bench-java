# TODO

## Status legend
- `[x]` complete
- `[ ]` not started

## Infrastructure

- [x] Cross-platform support (Windows, macOS, OpenShift containers).
- [x] Environment configuration with profile overrides and dotenv support.
- [x] Secrets management: OS keystores locally, HashiCorp Vault for OpenShift.

## WebUI

- [x] Dashboard with manager full-control and read-only viewer access.
- [x] GitHub repo management, JIRA connections, credential storage.
- [x] Bug fix analysis launcher, Copilot guide, model selection.
- [x] Field validation, connectivity tests, proxy settings management.
- [x] Renamed ADMIN to Manager. Download/submit endpoints for demo issues.

## Demo and API

- [x] Demo UI with sample banking app, issue selection, benchmarks, REST APIs.
- [x] Separate git repo with commit IDs. All 12 break/fix branches created.
- [x] Interactive verification with embedded CLI runner and demo-verify.sh script.
- [x] Banking app codebase statistics, start/stop controls, download/submit.

## Testing and Observability

- [x] AppMap instrumentation for WebUI and harness (enabled via APPMAP_ENABLED).
- [x] Comprehensive WebUI tests (5 test classes, 18 tests).
- [x] Step-by-step interactive verification with embedded CLI for demo issues.
- [x] Banking app codebase statistics on demo page.

## Current

- [x] Fix demo app startup error (troubleshoot and correct).

- [x] Add auto-refresh to demo app page so starting the app shows progress without manual refresh.

- [ ] Significantly scale the demo codebase. The target codebase and related issues must traverse code that is an order of magnitude larger than what fits in an LLM context window for effective testing. Otherwise the LLM can fit all related code into context and solve too easily, not representing real-world enterprise issues. **Target: 10M LOC** (roughly matches real-world megabank monorepo scale — ~250x current 38K LOC). *(In progress as of 2026-04-16; ~40K LOC source + ~1.5K LOC tests. Hand-written scaling is infeasible at this magnitude — requires templated code generation; see `docs/project-context/SCALING_PLAN.md`.)*

- [ ] **Demo-app AppMap recording + interactive viewer in the WebUI.**
  Add a section to the `/demo` page that lets a Manager kick off an
  AppMap recording against the demo banking-app and then browse the
  resulting traces interactively, without leaving the WebUI.

  Plumbing already in place: `banking-app/build.gradle.kts` applies
  `com.appland.appmap`, `banking-app/appmap.yml` configures package
  scope, and `harness-appmap` already walks `banking-app/**/tmp/appmap/`
  and emits `trace_index.json`. The new work is the WebUI surface.

  Implementation sketch:
  - **Record (Manager only)**:
    - "Record from tests" → POST `/demo/appmap/record-tests` runs
      `./gradlew test` for a chosen module (or all of banking-app)
      with `ORG_GRADLE_PROJECT_appmap_enabled=true`. Stream the gradle
      log to the same /demo log panel that's already wired.
    - "Record live HTTP" → POST `/demo/appmap/record-live/start` and
      `/stop` toggle the AppMap remote-recording endpoint that the
      AppMap Java agent exposes (`/_appmap/record` on the running
      banking-app), so a user can drive the running app from a browser
      and capture only the requests they care about.
  - **List view**: GET `/demo/appmap` walks every
    `banking-app/**/tmp/appmap/**.appmap.json`, extracts metadata
    (test class, event count, SQL count, HTTP count, size), and renders
    a sortable table. Reuse `harness-appmap`'s indexer if its API is
    callable from the WebUI module; otherwise scan directly.
  - **Interactive viewer**: GET `/demo/appmap/{traceId}` renders a
    full-page viewer.
    - **Recommended**: bundle the official `@appland/components` JS
      viewer (used by the AppMap VS Code extension) into
      `bench-webui/src/main/resources/static/appmap/` and pass the
      raw JSON to it via a `<script>` data island. Best fidelity, but
      adds a vendored JS bundle and confirms licensing for redistribution.
    - **Fallback**: render a server-side HTML call-tree (collapsible
      `<details>` per event) plus tabs for SQL statements and HTTP
      calls. No JS dependency, but lower fidelity than the official
      viewer.
  - **Authorization**: recording (writes to disk, runs gradle, hits the
    agent's record endpoint) requires Manager role; viewing is
    read-only and available to any logged-in role. Reuse the existing
    `AuthFilter` checks.

  **Main tradeoff** is the viewer choice: vendoring `@appland/components`
  buys real fidelity (call graph, sequence diagram, SQL inspector) at
  the cost of a JS bundle in the WebUI repo, while the server-side
  fallback is self-contained but lossy. Recommend starting with the
  fallback for the first pass to keep the WebUI dependency-light, then
  graduating to the embedded viewer once the recording flows are
  shaken out.

  **Status update**: first-pass shipped (commit 339e0fd). Server-side
  flat call-tree viewer + record-from-tests + raw .appmap.json
  download + interactive remote recording (start banking-app with
  `-javaagent:appmap-agent.jar`, drive from browser, save trace via
  `/_appmap/record`) all working. The official `@appland/components`
  viewer integration is wired in conditionally — viewer template
  detects `static/appmap/components.iife.js` and swaps in the Vue.js
  viewer when present, else falls back to the server-side tree.
  `scripts/vendor-appmap-viewer.sh` produces the bundle via npm +
  esbuild (it pulls @appland/components, esbuilds an IIFE that
  inlines all 15+ peer deps — Vue, Vuex, marked, mermaid,
  sql-formatter, highlight.js, dom-to-svg, dompurify, pako, sax,
  diff, etc.). Run once: `./scripts/vendor-appmap-viewer.sh` from the
  repo root; restart bench-webui; the viewer page picks it up
  automatically. Outstanding: wire that script into a CI step so the
  bundle is regenerated on @appland/components version bumps.
