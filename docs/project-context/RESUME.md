# Resume protocol

If this project has been interrupted and you're picking it up cold — human or AI — read this file first. It tells you exactly how to get back to productive work in one pass.

## 1. Read, in this order

1. `README.md` — high-level what and why
2. `docs/project-context/SPEC.md` — verbatim original user ask + interpretation decisions
3. `docs/project-context/DECISIONS.md` — non-obvious architectural choices and their reasons
4. `docs/project-context/BUILD-PLAN.md` — the master checklist; look for which boxes are ticked
5. `docs/architecture.md` — module map for banking-app and harness

At this point you have enough context to orient. Don't read everything else unless you're touching it.

## 2. Figure out what's already built

```bash
cd ~/VSCodeProjects/ai-bench-java
find . -type d -name 'src' | head -20          # which modules have source
ls banking-app/                                 # which modules exist at all
ls bench-harness/                               # harness progress
cat docs/project-context/BUILD-PLAN.md | grep -E '\[x\]|\[~\]'  # completed / in-progress
```

Prefer the filesystem as ground truth over BUILD-PLAN.md — the plan is a todo list, not an audit log. A box may be checked for something only half-done, or the plan may lag the code.

## 3. Pick up the next task

The build was designed to proceed phase-by-phase (see BUILD-PLAN.md). The strict dependency order is:

- Phase 0 docs (done first, easy)
- Phase 1 Gradle scaffolding (before any code compiles)
- Phase 2 shared modules (everyone depends on these)
- Phase 3 flagship modules (richer — most bugs target these)
- Phase 4 skeleton modules (can happen in parallel with 3)
- Phase 5 app edges (depends on 2 and 3/4)
- Phases 6–8 harness / cli / webui (largely independent of banking-app, but need their own Gradle scaffolding)
- Phase 9 VSCode bridge (independent TypeScript project)
- Phase 10 bug catalog (depends on flagship modules existing)
- Phase 11 smoke verification (last)

## 4. Work-in-progress sentinel

When you begin a task, overwrite `docs/project-context/IN-PROGRESS.md` with:

```
Task: <task name from BUILD-PLAN.md>
Started: <ISO timestamp>
Touching: <paths you expect to edit>
Notes: <anything a resumer would want to know mid-flight>
```

When you finish the task, tick the box in BUILD-PLAN.md and delete IN-PROGRESS.md. If an interrupted session finds IN-PROGRESS.md, it knows exactly where the previous session was when it stopped.

## 5. Conventions to preserve

- **Language split:** banking-app is Java 21; harness/webui/cli are Kotlin. Don't blur.
- **No OSS-lookalike code in banking-app.** Rename types, restructure packages, use Omnibank-idiosyncratic naming. The whole point is "not in training data." If you find yourself writing code that looks like a tutorial, stop and rewrite it differently.
- **Package root:** `com.omnibank.*` for target app; `com.aibench.*` for harness/cli/webui.
- **Bugs land as paired commits.** `bug/BUG-NNNN/break` then `bug/BUG-NNNN/fix`. Never squash. The git graph is part of the benchmark.
- **Flagship vs. skeleton.** Flagship modules have real logic; skeletons have interfaces + domain types + `throw new UnsupportedOperationException("TODO: BUG-xxxx landing site")` stubs. Don't accidentally flesh out a skeleton when you were supposed to be adding a bug.
- **Every flagship module has its own Flyway migrations** under `src/main/resources/db/migration/{module}/`.
- **Tests under `src/test/java`** for unit, `src/integrationTest/java` for Testcontainers-backed.

## 6. If the build breaks

Never paper over a broken build with `-x compileJava` or `--no-verify`. The harness scores by building; a green baseline is load-bearing. Fix the break.

## 7. Memory system

The user's cross-session memory lives at `~/.claude/projects/<project>/memory/`. For this project specifically:

- `user_ai_bench.md` — user's role/focus
- `project_ai_bench_java.md` — project overview (dual-purpose: bespoke + enterprise harness)
- `feedback_action_over_planning.md` — execute when scope is clear
- `reference_appmap.md` — AppMap tooling is installed

Before starting substantive work, check those. If any contradicts what's in this project's docs, the in-project docs win (they're more specific and more recent).
