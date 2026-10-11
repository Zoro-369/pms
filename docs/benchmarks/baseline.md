# Baseline benchmark (before fixes)

> Purpose: record how the system behaves **as it is today**, so later improvements (local JWT verification,
> caching, pagination, outbox) can be compared against real numbers.

## Environment

| Item | Value |
|---|---|
| Date | 2026-10-11 |
| Git commit | `8ab435f` |
| Machine | `mob-HP-Laptop-15s-eq2xxx` (x86_64, 12 vCPU, 7.25 GB RAM) |
| OS | Ubuntu 24.04.4 LTS / Linux 7.0.0-38-generic |
| Docker Desktop resources | N/A — local Linux host via `docker compose`; not Docker Desktop |
| Services | all 5 + 2 Postgres + Kafka via `docker compose` |
| Patients in DB before run | Not captured in the provided run log |
| Patients in DB after run | Not captured in the provided run log |

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