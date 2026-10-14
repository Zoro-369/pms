# PMS: Patient Management Microservices

A backend-only patient-management system built as five Spring Boot services (Java 21) behind a Spring Cloud Gateway,
using REST, gRPC and Kafka.

> **Origin and credit:** this project started from a structured microservices course and is being extended beyond it.
> The improvements I have made on top of the base are listed in [What I changed](#what-i-changed). Anything not listed there is course-base behaviour.

## Architecture

```
Client
  |  REST + Bearer JWT
  v
API Gateway :4004  (Spring Cloud Gateway, JwtValidation filter)
  |-- /auth/**          --> Auth Service :4005      --> Postgres (auth-service-db)
  |-- /api/v1/patients/**  --> Patient Service :4000   --> Postgres (patient-service-db)
  |        (JWT checked via Auth Service GET /validate)      |-- gRPC --> Billing Service :9001 (stub)
  |                                                          '-- Kafka topic "patient" (protobuf)
  |                                                                    |
  '-- /api-docs/*  (Swagger)                                           v
                                                              Analytics Service (logs events)
```

| Service | Port | Role |
|---|---|---|
| api-gateway | 4004 | Single entry point, routing, JWT check on `/api/v1/patients/**` |
| auth-service | 4005 | Login, JWT issue and validation (BCrypt, HS256) |
| patient-service | 4000 | Patient CRUD; calls billing over gRPC and publishes a Kafka event on create |
| billing-service | 4001 / 9001 | gRPC `CreateBillingAccount` (currently a stub) |
| analytics-service | 4002 | Kafka consumer of patient events (currently logs only) |

## Quickstart (Docker)

**Prerequisites:** Docker Desktop (or Docker Engine with the Compose plugin). The first build downloads Maven dependencies and can take several minutes.

```bash
# 1. Create your local config (never commit .env)
cp .env.example .env
# fill in both values, e.g. on macOS/Linux/Git Bash:
printf "JWT_SECRET=%s\nDB_PASSWORD=%s\n" "$(openssl rand -base64 32)" "$(openssl rand -hex 12)" > .env

# 2. Build and start everything
docker compose up --build -d

# 3. Run the smoke test (login, auth checks, create patient, billing + Kafka verification)
./scripts/smoke-test.sh
```

## API

All patient endpoints are under `/api/v1/patients` and need `Authorization: Bearer <token>`.

| Method | Path | Success | Errors |
|---|---|---|---|
| GET | `/api/v1/patients` | 200, paged list | 400 bad sort field, 401 |
| GET | `/api/v1/patients/{id}` | 200 | 400 bad id, 404 |
| POST | `/api/v1/patients` | 201 + `Location` | 400 validation, 409 duplicate email |
| PUT | `/api/v1/patients/{id}` | 200 | 400, 404, 409 |
| DELETE | `/api/v1/patients/{id}` | 204 | 404 |

**List parameters:** `page` (0-based), `size` (default 20, max 100), `sort=field,asc|desc`
(`name`, `email`, `dateOfBirth`, `registeredDate`), `search` (text in name or email, case-insensitive).

```json
{
  "content": [{"id": "...", "name": "Alice Johnson", "email": "alice.johnson@example.com",
               "address": "789 Oak St, Capital City", "dateOfBirth": "1978-03-12"}],
  "page": 0, "size": 20, "totalElements": 15, "totalPages": 1, "first": true, "last": true
}
```

Errors use RFC 7807 `application/problem+json`, for example:

```json
{"title": "Invalid request", "status": 400, "detail": "Validation failed",
 "errors": {"email": "Email should be valid"}}
```

Useful commands:

```bash
docker compose ps                          # status of all containers
docker compose logs -f patient-service     # follow one service
docker compose down                        # stop (keeps database volumes)
docker compose down -v                     # stop and wipe all data
```

Only the gateway (`localhost:4004`) is published for application traffic. The other services are reachable only inside the compose network.
Optional host ports for debugging: Postgres on `127.0.0.1:5001` (auth) and `5002` (patient), Kafka on `127.0.0.1:9094`, billing gRPC on `127.0.0.1:9001`.

### Try it manually

```bash
# Seeded user: testuser@test.com / password123 (role ADMIN). Development data only.
TOKEN=$(curl -s -X POST localhost:4004/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"testuser@test.com","password":"password123"}' | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')

curl -s localhost:4004/api/v1/patients -H "Authorization: Bearer $TOKEN"

curl -s -X POST localhost:4004/api/v1/patients -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"Jane Doe","email":"jane@example.com","address":"1 Main St","dateOfBirth":"1995-09-09","registeredDate":"2024-11-28"}'

docker compose logs analytics-service | grep jane@example.com   # the Kafka event arrived
```

API docs: `http://localhost:4004/api-docs/patients` and `http://localhost:4004/api-docs/auth` (OpenAPI JSON).
Sample requests for an HTTP client are in `api-requests/` (their base URL points at the LocalStack load balancer; change it to `http://localhost:4004`).

## Create-patient flow

1. Validate the request (Bean Validation) and check for a duplicate email.
2. Save the patient in `patient-service-db`.
3. Call billing-service over gRPC to create a billing account.
4. Publish a `PATIENT_CREATED` protobuf event to Kafka topic `patient`.
5. Return the saved patient.

## AWS / LocalStack deployment (alternative path)

`infrastructure/` contains an AWS CDK (Java) stack that provisions a VPC, two RDS Postgres instances, an MSK cluster and ECS Fargate services,
deployed to LocalStack with `infrastructure/localstack-deploy.sh`. It is separate from the Docker Compose setup above.

## Known limitations (being addressed)

- `createPatient` is not atomic across the database, gRPC and Kafka (no transactional outbox yet).
- gRPC calls have no deadline, retry or circuit breaker.
- Billing and analytics are stubs.
- The JWT `role` claim is issued but not enforced (no RBAC); the signing secret is a shared symmetric key.
- `GET /api/v1/patients` is not paginated; some error responses use 400 where 404 or 409 is more appropriate.
- Test coverage is limited to a few integration tests that need the full stack running.

## What I changed
- **Docker Compose stack** (Postgres x2, single-node Kafka, all services), `.env`-based secrets and an end-to-end smoke test.
- **API fixes:** correct status codes (404, 409, 201), RFC 7807 error responses, typed dates, validation on login.
- **Flyway migrations** replacing `ddl-auto=update` and `data.sql`; dev seed data isolated from the schema.
- **Pagination, sorting and search**, and API versioning under `/api/v1`.
- **k6 benchmarks** with a documented baseline, and a warm-up fix after finding a cold-start p99 artifact.

## Database

Schema is managed by Flyway (`db/migration`); Hibernate only validates it (`ddl-auto=validate`).
Sample data lives in `db/seed` and is enabled only in Docker Compose and LocalStack, never in the base migrations.

## Testing and benchmarks

- `./scripts/smoke-test.sh`: end-to-end checks against the running stack (auth, CRUD, status codes, paging, billing and Kafka side effects).
- `mvn -f integration-tests/pom.xml test`: RestAssured tests through the gateway (stack must be running).
- `k6 run perf/k6/baseline.js`: load test; results and analysis in [docs/benchmarks/baseline.md](docs/benchmarks/baseline.md).
- 
## Project layout

```
api-gateway/  auth-service/  patient-service/  billing-service/  analytics-service/
infrastructure/   integration-tests/   api-requests/   grpc-requests/
docker-compose.yml   .env.example   scripts/smoke-test.sh
```