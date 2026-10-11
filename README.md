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
  |-- /api/patients/**  --> Patient Service :4000   --> Postgres (patient-service-db)
  |        (JWT checked via Auth Service GET /validate)      |-- gRPC --> Billing Service :9001 (stub)
  |                                                          '-- Kafka topic "patient" (protobuf)
  |                                                                    |
  '-- /api-docs/*  (Swagger)                                           v
                                                              Analytics Service (logs events)
```

| Service | Port | Role |
|---|---|---|
| api-gateway | 4004 | Single entry point, routing, JWT check on `/api/patients/**` |
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

curl -s localhost:4004/api/patients -H "Authorization: Bearer $TOKEN"

curl -s -X POST localhost:4004/api/patients -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
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
- `GET /api/patients` is not paginated; some error responses use 400 where 404 or 409 is more appropriate.
- Test coverage is limited to a few integration tests that need the full stack running.

## What I changed

- Added a Docker Compose setup (Postgres x2, single-node Kafka, all services), an `.env`-based configuration for secrets, and an end-to-end smoke test.
- Set the analytics service port explicitly so it matches its Dockerfile.
- Added a baseline performance benchmark (k6) and documented the results.
- Fixed API status codes, error format, date handling and login validation.
## Project layout

```
api-gateway/  auth-service/  patient-service/  billing-service/  analytics-service/
infrastructure/   integration-tests/   api-requests/   grpc-requests/
docker-compose.yml   .env.example   scripts/smoke-test.sh
```