// k6 baseline load test for PMS (through the API gateway).
// Run BEFORE changing any code on Day 2 so the numbers describe the course-base behaviour.
//
//   brew install k6            (macOS)   |   winget install k6   (Windows)
//   k6 run perf/k6/baseline.js
//   k6 run -e BASE_URL=http://localhost:4004 perf/k6/baseline.js
//
// NOTE (Day 4): list now returns one page (20 rows) instead of every patient.
// Three scenarios run at the same time, each at a fixed request rate:
//   login          5 req/s   (BCrypt is deliberately slow, so keep this low)
//   list_patients  20 req/s  (returns EVERY patient, so it slows down as data grows)
//   create_patient 10 req/s  (DB save + gRPC billing + Kafka event)
// Results are tagged per scenario and written to perf/results/.

import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE_URL || 'http://localhost:4004';
const DURATION = __ENV.DURATION || '60s';
const JSON_HEADERS = { 'Content-Type': 'application/json' };

function scenario(rate, exec) {
    return {
        executor: 'constant-arrival-rate',
        rate: rate,
        timeUnit: '1s',
        duration: DURATION,
        preAllocatedVUs: 20,
        maxVUs: 200,
        exec: exec,
    };
}

export const options = {
    scenarios: {
        login: scenario(5, 'login'),
        list_patients: scenario(20, 'listPatients'),
        create_patient: scenario(10, 'createPatient'),
    },
    summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
    // Lenient thresholds: a baseline should record numbers, not fail. They also force k6 to
    // keep per-scenario sub-metrics so handleSummary can report them.
    thresholds: {
        'http_req_duration{scenario:login}': ['p(95)<60000'],
        'http_req_duration{scenario:list_patients}': ['p(95)<60000'],
        'http_req_duration{scenario:create_patient}': ['p(95)<60000'],
        'http_req_failed{scenario:login}': ['rate<1'],
        'http_req_failed{scenario:list_patients}': ['rate<1'],
        'http_req_failed{scenario:create_patient}': ['rate<1'],
        'http_reqs{scenario:login}': ['count>=0'],
        'http_reqs{scenario:list_patients}': ['count>=0'],
        'http_reqs{scenario:create_patient}': ['count>=0'],
        'data_received{scenario:login}': ['count>=0'],
        'data_received{scenario:list_patients}': ['count>=0'],
        'data_received{scenario:create_patient}': ['count>=0'],
    },
};

// Log in once; the list/create scenarios reuse the token (valid for 10 hours).
export function setup() {
    const res = http.post(`${BASE}/auth/login`,
        JSON.stringify({ email: 'testuser@test.com', password: 'password123' }), { headers: JSON_HEADERS });
    if (res.status !== 200) {
        throw new Error(`Setup login failed with HTTP ${res.status}. Is the stack up (docker compose ps)?`);
    }
    const token = res.json('token');

    // Warm-up (not measured): the first protected requests pay one-off costs (JIT, class loading,
    // connection pools, Kafka producer metadata, gRPC channel connect). Without this, a cold start
    // shows up as a ~1 s p99 spike that says nothing about steady-state behaviour.
    const auth = { headers: { ...JSON_HEADERS, Authorization: `Bearer ${token}` } };
    for (let i = 0; i < 30; i++) {
        http.get(`${BASE}/api/v1/patients`, auth);
    }
    for (let i = 0; i < 3; i++) {
        http.post(`${BASE}/api/v1/patients`, JSON.stringify({
            name: 'K6 Warmup', email: `k6-warmup-${Date.now()}-${i}@example.com`, address: '1 Warmup St',
            dateOfBirth: '1995-09-09', registeredDate: '2024-11-28',
        }), auth);
    }
    return { token: token };
}

export function login() {
    const res = http.post(`${BASE}/auth/login`,
        JSON.stringify({ email: 'testuser@test.com', password: 'password123' }), { headers: JSON_HEADERS });
    check(res, { 'login 200': (r) => r.status === 200 });
}

export function listPatients(data) {
    const res = http.get(`${BASE}/api/v1/patients`, { headers: { Authorization: `Bearer ${data.token}` } });
    check(res, { 'list 200': (r) => r.status === 200 });
}

export function createPatient(data) {
    const email = `k6-${__VU}-${__ITER}-${Date.now()}@example.com`;
    const body = JSON.stringify({
        name: 'K6 Load Test',
        email: email,
        address: '1 Benchmark Street',
        dateOfBirth: '1995-09-09',
        registeredDate: '2024-11-28',
    });
    const res = http.post(`${BASE}/api/v1/patients`, body,
        { headers: { ...JSON_HEADERS, Authorization: `Bearer ${data.token}` } });
    // 200 before the Day 2 fixes, 201 after; accept both so the same script works for the comparison run.
    check(res, { 'create 200/201': (r) => r.status === 200 || r.status === 201 });
}

function val(data, metric, stat) {
    const m = data.metrics[metric];
    if (!m || m.values[stat] === undefined) return 'n/a';
    return m.values[stat];
}

function ms(v) { return v === 'n/a' ? v : v.toFixed(1); }

export function handleSummary(data) {
    const seconds = (data.state && data.state.testRunDurationMs ? data.state.testRunDurationMs : 60000) / 1000;
    const rows = ['login', 'list_patients', 'create_patient'].map((s) => {
        const d = `http_req_duration{scenario:${s}}`;
        const count = val(data, `http_reqs{scenario:${s}}`, 'count');
        const rps = count === 'n/a' ? 'n/a' : (count / seconds).toFixed(1);
        const failed = val(data, `http_req_failed{scenario:${s}}`, 'rate');
        const failPct = failed === 'n/a' ? 'n/a' : (failed * 100).toFixed(2) + '%';
        const bytes = val(data, `data_received{scenario:${s}}`, 'count');
        const kb = (bytes === 'n/a' || count === 'n/a' || count === 0) ? 'n/a' : (bytes / count / 1024).toFixed(1);
        return `| ${s} | ${count} | ${rps} | ${ms(val(data, d, 'med'))} | ${ms(val(data, d, 'p(95)'))} | ${ms(val(data, d, 'p(99)'))} | ${kb} | ${failPct} |`;
    });
    const table = [
        '| Scenario | Requests | Achieved req/s | p50 (ms) | p95 (ms) | p99 (ms) | KB per response | Failed |',
        '|---|---|---|---|---|---|---|---|',
        ...rows,
    ].join('\n');
    const label = __ENV.RUN_LABEL || 'baseline';
    return {
        stdout: `\n${table}\n\n(also saved to perf/results/${label}-table.md and ${label}-summary.json)\n`,
        [`perf/results/${label}-table.md`]: table + '\n',
        [`perf/results/${label}-summary.json`]: JSON.stringify(data, null, 2),
    };
}