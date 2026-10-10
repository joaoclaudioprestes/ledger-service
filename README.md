# ledger-service

> Double-entry ledger API in Spring Boot 3: concurrency-safe transfers, idempotency keys, Testcontainers, and production observability with Micrometer, OpenTelemetry and Grafana.

![Java 21](https://img.shields.io/badge/java-21-blue)
![Maven](https://img.shields.io/badge/maven-3.9-C71A36)
![Tests](https://img.shields.io/badge/tests-10%20passed-brightgreen)
[![CI](https://github.com/joaoclaudioprestes/ledger-service/actions/workflows/ci.yml/badge.svg)](https://github.com/joaoclaudioprestes/ledger-service/actions/workflows/ci.yml)
![License: MIT](https://img.shields.io/badge/license-MIT-green)

---

## Stack

| Layer | Technology |
|---|---|
| Language | [Java 21](https://openjdk.org/projects/jdk/21/) |
| Framework | [Spring Boot 3.4](https://spring.io/projects/spring-boot) (Web, Validation, JDBC) |
| Database | [PostgreSQL 16](https://www.postgresql.org/) |
| Migrations | [Flyway](https://flywaydb.org/) |
| Build | [Maven](https://maven.apache.org/) |
| Tests | JUnit 5, AssertJ, [Testcontainers](https://testcontainers.com/) |
| CI | [GitHub Actions](https://github.com/features/actions) |

---

## Architecture

```
HTTP client
    │
    ▼
LedgerController   validation, status codes, ProblemDetail errors
    │
    ▼
LedgerService      transactions, locking, idempotency
    │
    ▼
PostgreSQL         accounts · entries · idempotency_keys (Flyway-managed)
```

```
src/
├── main/
│   ├── java/com/prestes/ledger/
│   │   ├── LedgerApplication.java   # Spring Boot entry point
│   │   ├── LedgerController.java    # REST endpoints + error mapping
│   │   └── LedgerService.java       # double-entry transfers, idempotency
│   └── resources/
│       ├── application.yml          # datasource config (env vars)
│       └── db/migration/            # V1 accounts, V2 entries, V3 balance + SYSTEM, V4 idempotency_keys
└── test/java/com/prestes/ledger/
    └── LedgerTest.java              # integration tests against real Postgres
```

Key decisions:

- **Double entry.** Every transfer writes one `DEBIT` and one `CREDIT` entry sharing a `transaction_id`, in a single DB transaction. Deposits move money from a `SYSTEM` account (the only one allowed below zero), so the ledger always nets to zero.
- **Materialized balance.** `accounts.balance_cents` is updated in the same transaction as the entries; a test checks it equals the sum of entries.
- **Pessimistic locking.** Both account rows are locked with `SELECT ... FOR UPDATE` in id order: no deadlocks, and hot accounts serialize instead of retrying.
- **Overdraft guard in two layers.** The service rejects it (422); a `CHECK` constraint backs it up in the DB.
- **Idempotency.** `POST /transfers` requires an `Idempotency-Key`. The key is stored with a request hash in the transfer's own transaction: same key + same payload returns the original `transactionId`; same key + different payload returns 422. Keys expire after 24h and are purged hourly.
- **Money as integer cents** (`BIGINT`), never floats.

---

## Installation

### Prerequisites

- Java 21+
- Maven 3.9+
- Docker (for PostgreSQL and for the tests)

### Setup

```bash
git clone git@github.com:joaoclaudioprestes/ledger-service.git
cd ledger-service

# start PostgreSQL (matches the defaults below)
docker run -d --name ledger-db -p 5432:5432 \
  -e POSTGRES_DB=ledger -e POSTGRES_USER=ledger -e POSTGRES_PASSWORD=ledger \
  postgres:16-alpine

# run the API on :8080 (Flyway applies migrations on startup)
mvn spring-boot:run
```

### Environment variables

| Variable | Default |
|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/ledger` |
| `DB_USER` | `ledger` |
| `DB_PASSWORD` | `ledger` |

---

## Usage

Create two accounts and fund the first one:

```bash
curl -s -X POST localhost:8080/accounts -H 'Content-Type: application/json' -d '{"name":"alice"}'
# {"id":"<ALICE>","name":"alice"}

curl -s -X POST localhost:8080/accounts -H 'Content-Type: application/json' -d '{"name":"bob"}'
# {"id":"<BOB>","name":"bob"}

curl -s -X POST localhost:8080/accounts/<ALICE>/deposits -H 'Content-Type: application/json' -d '{"amountCents":1000}'
# {"transactionId":"<TX>"}
```

Transfer (the `Idempotency-Key` header is mandatory; retrying with the same key does not transfer twice):

```bash
curl -s -X POST localhost:8080/transfers \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: order-42' \
  -d '{"from":"<ALICE>","to":"<BOB>","amountCents":300}'
# {"transactionId":"<TX>"}   (201, same id on retry)
```

Balance and statement:

```bash
curl -s localhost:8080/accounts/<BOB>/balance
# {"accountId":"<BOB>","balanceCents":300}

curl -s 'localhost:8080/accounts/<BOB>/entries?page=0&size=20'
# {"items":[{"id":"...","transactionId":"<TX>","accountId":"<BOB>","direction":"CREDIT","amountCents":300,"createdAt":"..."}],"page":0,"size":20,"total":1}
```

| Method | Path | Success | Errors |
|---|---|---|---|
| `POST` | `/accounts` | 201 | 400 |
| `POST` | `/accounts/{id}/deposits` | 201 | 400, 404 |
| `POST` | `/transfers` | 201 | 400 (bad body / missing key / same account), 404, 422 (insufficient funds, key reused with different payload) |
| `GET` | `/accounts/{id}/balance` | 200 | 404 |
| `GET` | `/accounts/{id}/entries` | 200 (`page`, `size` ≤ 100) | 400, 404 |

All errors (404, 422, constraint violations, same-account transfers, malformed bodies, missing `Idempotency-Key`) return RFC 9457 `ProblemDetail` (`application/problem+json`).

**Traces:** `docker compose up`, call the API, then open Jaeger at http://localhost:16686 and pick service `ledger-service`.

---

## Development

```bash
mvn spring-boot:run   # run the API
mvn test              # run the tests
mvn -B verify         # what CI runs
```

### Tests

10 integration tests (`LedgerTest`) run the full Spring context against a real PostgreSQL 16 container via Testcontainers, no mocks. Docker must be running.

They cover:

- balance equals the sum of entries and the ledger nets to zero
- paginated statement
- overdraft rejection, including concurrent overdraft attempts that never go negative
- 1000 concurrent transfers closing on the exact balance
- invalid transfers (400 / 404)
- idempotency: same key transfers once, different payload 422, missing/blank/oversized key 400, expired key executes again

---

## License

[MIT](LICENSE)
