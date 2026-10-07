# RTGS Liquidity & Settlement Optimization Engine

One Java 21 / Spring Boot backend that settles high-value payment instructions while participants share finite intraday liquidity. Money is represented as `long` minor units. The implementation follows the [revised core-first handoff](docs/revised-core-first-handoff.md).

## 1. Problem

If A has 100 units and concurrent instructions try to transfer 70 and 60, both must not spend the same liquidity. The engine protects balances, queues unfunded payments, applies deterministic priorities, and resolves feasible groups of blocked obligations.

## 2. Basic settlement

With A=100 and B=0, A→B=40 leaves A=60 and B=40. Insufficient liquidity leaves balances unchanged and marks the instruction `QUEUED`. Payment IDs are idempotency keys; reusing an ID with different financial content is rejected.

Early development needs only Java 21 and Maven:

```text
mvn clean test
mvn spring-boot:run
```

`GET http://localhost:8080/health` returns `{"status":"UP"}`. The default profile keeps state in memory and requires no external service. State disappears when that profile restarts. On Windows, ensure `JAVA_HOME` points to the Java 21 installation if the default `java` launcher points elsewhere.

Register opening positions and send an instruction using PowerShell:

```powershell
$base='http://localhost:8080'
function Post-Rtgs($path,$body) {
  Invoke-RestMethod -Method Post -Uri ($base+$path) -ContentType 'application/json' -Body ($body | ConvertTo-Json)
}
Post-Rtgs '/api/participants' @{participantId='A';displayName='Bank A';openingLiquidityMinor=100}
Post-Rtgs '/api/participants' @{participantId='B';displayName='Bank B';openingLiquidityMinor=0}
Post-Rtgs '/api/payments' @{paymentId='p1';sourceParticipantId='A';destinationParticipantId='B';amountMinor=40;priority='NORMAL'}
```

## 3. Concurrency

Every financial operation collects participant IDs, removes duplicates, sorts the IDs, acquires locks in that order, and releases them in reverse. Payment state is rechecked inside the protected operation. PostgreSQL transactions and constraints provide the durable boundary when persistence is enabled. See [concurrency](docs/concurrency.md).

## 4. Priority scheduling

Queued instructions rank by `URGENT`, `HIGH`, `NORMAL`, then deadline (missing last), queued time, and payment ID. The scheduler rescans after a settlement and stops when no instruction can progress. Liquidity injection and incoming settlement credits recheck blocked work.

```powershell
Post-Rtgs '/api/liquidity/A/inject' @{amountMinor=40}
Post-Rtgs '/api/scheduler/run' @{}
```

## 5. Gridlock

Queued instructions form a directed graph. Tarjan's algorithm identifies strongly connected components. The resolver computes final net positions, prunes infeasible candidates deterministically, then commits a valid batch while holding all participant locks. A cycle alone is insufficient evidence for settlement. See the [exact algorithm and pruning policy](docs/gridlock-algorithm.md).

`POST /api/gridlock/run` runs detection and resolution. Equal A→B→C→A obligations can settle with zero opening liquidity when the final net positions are nonnegative.

## 6. PostgreSQL

The `postgres` profile uses Flyway migrations and PostgreSQL as the source of truth. Transactions cover debit, credit, settlement insertion, and payment status. Gridlock batches use one transaction. Schema constraints prohibit negative liquidity and multiple settlements for one payment.

Set `RTGS_DB_URL`, `RTGS_DB_USER`, and `RTGS_DB_PASSWORD`, then run:

```text
mvn spring-boot:run -Dspring-boot.run.profiles=postgres
```

## 7. Kafka

Use profiles `postgres,kafka` for asynchronous ingestion. REST returns the accepted `RECEIVED` instruction after broker acceptance. The consumer calls the durable engine. Poll the payment endpoint for the processing result. Instructions are keyed by source participant. Bounded retries route failed input to `payment.dlq`. See [Kafka semantics](docs/kafka-semantics.md).

## 8. Redis

Add the `redis` profile for committed liquidity snapshots, a queue index, and settled-payment hints. A positive hint is checked against PostgreSQL before it can bypass a transaction. Cache failures preserve durable correctness. `POST /api/admin/redis/rebuild` reconstructs state from PostgreSQL. See [Redis's role](docs/redis-role.md).

## 9. Docker

The complete environment uses PostgreSQL 17, Kafka 3.9.2 in KRaft mode, Redis 7.4, and a Java 21 application container:

```text
docker compose config --quiet
docker compose up -d --build
docker compose ps
```

Ports: API 8080, PostgreSQL 15432, Kafka 19092, Redis 16379. Compose credentials are local demonstration defaults. `docker compose down` stops the environment while retaining database and Kafka volumes.

```powershell
./scripts/runtime-smoke.ps1
./scripts/failure-recovery.ps1
```

The recovery script deliberately restarts the application, clears this environment's Redis database, and injects then removes database failure triggers. Run it against this project's dedicated test environment.

## 10. Benchmarking

All workloads are **MODELED ASSUMPTION**, never private bank traces. The six profiles are normal-day, peak-day, burst-day, liquidity-constrained, gridlock-heavy, and duplicate-heavy. Configuration is a Java record and the CLI has profile/count/seed/output arguments.

```text
mvn compile exec:java -Dexec.mainClass=com.rtgs.benchmark.GeneratorMain "-Dexec.args=normal-day 1000 42 target/workload.json"
mvn compile exec:java -Dexec.mainClass=com.rtgs.benchmark.AlgorithmBenchmarkMain
mvn compile exec:java -Dexec.mainClass=com.rtgs.benchmark.EndToEndBenchmarkMain
```

See [methodology](docs/benchmark-methodology.md), [stored results](docs/benchmark-results), and [implementation status](docs/implementation-status.md). These small local runs are correctness and comparative measurements, not maximum-capacity claims.

## API

| Method | Path | Availability |
| --- | --- | --- |
| GET | `/health` | All profiles |
| POST | `/api/participants` | Register opening position |
| POST | `/api/payments` | Sync by default; async with Kafka |
| GET | `/api/payments/{paymentId}` | Payment state |
| GET | `/api/payments?status=QUEUED` | Ordered queue |
| GET | `/api/liquidity/{participantId}` | Authoritative balance |
| POST | `/api/liquidity/{participantId}/inject` | Inject and schedule |
| GET | `/api/settlements/{paymentId}` | Settlement record |
| POST | `/api/scheduler/run` | Retry queue |
| POST | `/api/gridlock/run` | Detect and resolve |
| POST | `/api/admin/redis/rebuild` | Redis profile |
| GET | `/api/stats` | PostgreSQL profile |
| GET | `/api/admin/dependencies` | Full infrastructure profiles |

## Tests

Ordinary `mvn test` needs no running infrastructure. Optional tests expect a disposable PostgreSQL database `rtgs` on 127.0.0.1:55432, user `rtgs`, with trust authentication. They use embedded Kafka and a Redis protocol test server; native services are also checked by the Compose scripts.

```text
mvn test -Drtgs.postgres.test=true -Drtgs.kafka.test=true -Drtgs.redis.test=true
```

The tested Windows setup used `initdb` and `pg_ctl` to create a separate workspace cluster; see [verification commands](docs/phase-reports.md). The optional integration flags are explicit so early core work stays infrastructure independent.
