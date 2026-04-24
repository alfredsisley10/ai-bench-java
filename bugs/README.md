# Bug catalog

Each `BUG-NNNN.yaml` is a benchmark task. See [docs/bug-catalog.md](../docs/bug-catalog.md) for the schema and [docs/eval-protocol.md](../docs/eval-protocol.md) for how runs are scored.

## Current catalog (12 samples)

| id | module | difficulty | category | AppMap uplift hypothesis |
|---|---|---|---|---|
| BUG-0001 | payments-hub | MEDIUM | TIMING | high |
| BUG-0002 | shared-domain | EASY | NUMERIC | low |
| BUG-0003 | ledger-core | HARD | STATE_MACHINE | high |
| BUG-0004 | shared-domain | MEDIUM | NUMERIC | low |
| BUG-0005 | accounts-consumer | MEDIUM | TIMING | high |
| BUG-0006 | lending-corporate | EASY | STATE_MACHINE | medium |
| BUG-0007 | lending-corporate | MEDIUM | NUMERIC | low |
| BUG-0008 | payments-hub | HARD | CONCURRENCY | very high |
| BUG-0009 | ledger-core | MEDIUM | N_PLUS_ONE | very high |
| BUG-0010 | payments-hub | EASY | LOGIC | low |
| BUG-0011 | shared-domain | TRIVIAL | NUMERIC | low |
| BUG-0012 | accounts-consumer | TRIVIAL | LOGIC | low |

The catalog spans every flagship module and every difficulty tier. The AppMap uplift hypothesis column records the pre-experiment prediction — once benchmark runs execute, we can compare measured uplift to the prior.

## Adding a new bug

1. Pick the next `BUG-NNNN.yaml` slot.
2. Write the break commit that introduces the defect on a branch named `bug/BUG-NNNN/break`.
3. Write the fix commit (plus the hidden test that verifies the fix) on `bug/BUG-NNNN/fix`.
4. Author `bugs/BUG-NNNN.yaml` with the metadata matching [docs/bug-catalog.md](../docs/bug-catalog.md).
5. Validate: `bench catalog show BUG-NNNN` + `bench solve --bug BUG-NNNN --solver corp-openai --appmap OFF` smoke-runs against the harness.
