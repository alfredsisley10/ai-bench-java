# AppMap integration

[AppMap](https://appmap.io) records runtime call graphs, SQL statements, and HTTP requests during test execution. Traces serialize to `tmp/appmap/**/*.appmap.json` per test. The central hypothesis of this benchmark is that surfacing those traces to an LLM improves its ability to localize and fix bugs, particularly for categories where static code-reading misses runtime behavior (timing, concurrency, state-machine, N+1).

## Wiring into the target app

`banking-app/build.gradle.kts`:

```kotlin
plugins {
    id("com.appland.appmap") version "1.+" apply false
}

subprojects {
    apply(plugin = "com.appland.appmap")
    // By default the AppMap Gradle plugin wires the java agent into :test and :integrationTest
}
```

Plus `banking-app/appmap.yml`:

```yaml
name: omnibank
packages:
  - path: com.omnibank
    # Exclude noisy framework paths but keep our own code
    exclude:
      - com.omnibank.shared.testing
```

The harness does **not** leave AppMap on by default — it is enabled per-run via `ORG_GRADLE_PROJECT_appmap_enabled=true` (the plugin honors this).

## Collection

After the armed test run completes, `harness-appmap` walks `banking-app/**/tmp/appmap/` and indexes:

```
trace_index.json:
[
  {
    "test_class": "com.omnibank.payments.ach.AchSubmissionServiceTest",
    "test_method": "submits_before_cutoff_accepted",
    "file": "payments-hub/tmp/appmap/junit/...json",
    "size_bytes": 184321,
    "event_count": 842,
    "sql_count": 14,
    "http_count": 0
  },
  ...
]
```

## Injection into solver prompt

Raw `.appmap.json` files are large (often 100–500KB per trace) and verbose. Dumping them wholesale blows the context budget. `harness-appmap` offers three injection strategies:

1. **Attach raw** — pass the JSON as a file attachment (for solvers with large context + file input support). Best fidelity.
2. **Compact** — extract call-tree skeleton + SQL list + HTTP list, stripping arg payloads >N chars. Good default.
3. **Focused** — given a target symbol (from `bug.appmap.trace_focus`), emit only traces that pass through it, with calling/called context. Lowest tokens.

The harness records which strategy a run used, so we can correlate injection mode with pass rates.

## Enterprise-repo support

Projects that do not already use AppMap get the agent wired in at build time by `harness-builder` — it writes a `build/ai-bench/appmap.yml` next to the root build file, and either applies the Gradle plugin via an init script or prepends `-javaagent:<appmap-agent>` to `JAVA_OPTS` for Maven.

Projects that **do** already use AppMap are detected (presence of root `appmap.yml`) and left alone.

## Limitations

- Traces capture only what exercised tests touched. Bugs in unexercised code paths get no AppMap benefit — a known limitation we want to quantify.
- Very large traces from slow tests can exceed solver context even in `focused` mode; the harness warns and falls back to `compact` when projected tokens exceed a configurable cap.
