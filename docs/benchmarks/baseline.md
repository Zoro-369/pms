# Baseline benchmark (before fixes)

> Purpose: record how the system behaves **as it is today**, so later improvements (local JWT verification,
> caching, pagination, outbox) can be compared against real numbers.

## Environment

| Item | Value                                                           |
|---|-----------------------------------------------------------------|
| Date | 2026-10-11                                                      |
| Git commit | `8ab435f`                                                       |
| Machine | `HP-Laptop-15s`                                                 |
| OS | Ubuntu 24.04.4 LTS / Linux 7.0.0-38-generic                     |
| Docker Desktop resources | N/A — local Linux host via `docker compose`; not Docker Desktop |
| Services | all 5 + 2 Postgres + Kafka via `docker compose`                 |
| Patients in DB before run | 15                                                              |
| Patients in DB after run | 662                                                             |

## Method

- Tool: k6, script `perf/k6/baseline.js`, run against the gateway at `http://localhost:4004`.
- Three scenarios at fixed arrival rates for 60 s: login 5 req/s, list patients 20 req/s, create patient 10 req/s.
- Every protected request goes through the gateway JWT filter, which calls the auth service `/validate` (one extra network hop per request).
- Command: `k6 run perf/k6/baseline.js`

## Results

| Scenario | Requests | Achieved req/s | p50 (ms) | p95 (ms) | p99 (ms) | Failed |
|---|---|---|---|---|---|---|
| login | 301 | 5.0 | 251.7 | 270.4 | 283.7 | 0.00% |
| list_patients | 1201 | 19.9 | 11.3 | 15.7 | 19.2 | 0.00% |
| create_patient | 601 | 9.9 | 14.6 | 20.3 | 26.3 | 0.00% |

## Observations

- The slowest endpoint is `login`, which is expected: BCrypt is deliberately expensive and dominates the latency profile.
- The achieved req/s closely matched the target rates: login 5.0 req/s, list_patients 19.9 req/s, create_patient 9.9 req/s. No scenario materially fell behind the target.
- `list_patients` stays fast in the median and p95 range, but the cost is still sensitive to dataset size because it returns every patient with no pagination; the create scenario adds rows during the run, so the list workload becomes gradually more expensive over time.
- There were no failed requests. All scenarios stayed at 0.00% failure, with no HTTP status-code errors recorded in the log.

## Known caveats

- Everything runs on one machine inside Docker, so numbers are only comparable with runs on the same machine and settings.
- The list scenario's cost grows as the create scenario adds patients; this is a property of the current design, not noise.
- First requests after startup include JVM warm-up; run the test twice and record the second run.

## Day 4 comparison: pagination, sorting and search

Same machine, same scenarios (login 5/s, list 20/s, create 10/s for 60 s), run through the gateway.
"After" is the second run after a fresh start, with the warm-up described below.

| Metric | Day 2 baseline | After Day 4 |
|---|---|---|
| list p50 / p95 / p99 (ms) | 11.3 / 15.7 / 19.2 | 6.5 / 9.4 / 12.3 |
| create p50 / p95 / p99 (ms) | 14.6 / 20.3 / 26.3 | 10.1 / 15.1 / 20.3 |
| login p50 / p95 / p99 (ms) | 251.7 / 270.4 / 283.7 | 246.7 / 258.8 / 274.3 |
| list response size | not measured | 3.5 KB (20 rows) |
| failed requests | 0% | 0% |

Run-to-run range over <N> runs (fill in after repeating): list p50 <min>-<max> ms, list p95 <min>-<max> ms.

### Interpretation

- **Latency:** list p50 and p95 dropped, but create dropped by a similar amount (about 30%) with almost no code change,
  so much of the difference is run-to-run variation and warm-up rather than pagination. I am not claiming a latency
  improvement from pagination.
- **What pagination changes:** the work and payload per request are now bounded. A list call returns about 3.5 KB
  (20 rows) however large the table grows. Before, it returned every patient (about 600-1,200 rows in the recorded
  baseline run; roughly 175 bytes per row, so on the order of 100+ KB per call; this is an estimate, not a measurement).
- **Cold start:** the first run after a rebuild showed p99 of about 1.2 s for list and create, while login was normal.
  Only list and create go through the gateway's token validation, and the first validations pay one-off costs
  (JIT, class loading, connection setup, Kafka producer and gRPC channel initialisation). I added an unmeasured
  warm-up to the k6 `setup()`, and p99 fell to 12 ms (list) and 20 ms (create).

### Limits of this benchmark

- The table holds about 1,000 rows, too small for pagination or indexes to matter for latency.
  Day 27 re-measures with a large dataset, indexes and keyset pagination.
- Single machine, everything in Docker, so numbers only compare with runs on the same setup.

### Reproduce

```bash
docker compose down -v && docker compose up -d
RUN_LABEL=after-day4 k6 run perf/k6/baseline.js   # run twice, record the second
```