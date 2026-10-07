# RTGS Liquidity & Settlement Optimization Engine
## Revised Complete LLM Implementation Handoff — Core First, Scalability Later

> **Purpose**
>
> This document is the authoritative implementation handoff for the project.
> It replaces the earlier implementation order that introduced Docker and infrastructure too early.
>
> The required implementation philosophy is:
>
> **Build the actual banking/settlement system first in Java + Spring Boot, prove the algorithms and concurrency, then add PostgreSQL, Kafka, Redis, Docker, and performance/scalability layers one by one.**

---

# 1. Project Title

**RTGS Liquidity & Settlement Optimization Engine**

Recommended resume stack line:

> Java 21 | Spring Boot | Java Concurrency | Kafka | Redis | PostgreSQL | Docker

---

# 2. Project Goal

Build a backend system that simulates an RTGS-style high-value payment settlement environment where banks/participants have limited intraday liquidity.

The system must decide:

- which payments can settle immediately,
- which must wait,
- which queued payments should receive priority,
- how incoming liquidity affects blocked payments,
- how concurrent requests safely share limited liquidity,
- and whether mutually blocked payments can be settled together through gridlock resolution.

The project should demonstrate deep Java/backend engineering rather than merely combining many technologies.

---

# 3. Core Problem Statement

High-value payment systems operate with finite liquidity.

Example:

```text
BANK_A available liquidity = 100

P1: A -> B = 70
P2: A -> C = 60
```

If both are processed concurrently, naive code could let both observe the same 100 units and approve both, producing negative liquidity.

This must never happen.

Payments may also become mutually blocked:

```text
A -> B = 70
B -> C = 60
C -> A = 50
```

Individually, some payments may remain queued even though the group is feasible when considered together.

The system therefore solves:

> **How can high-value payment instructions be processed concurrently under finite liquidity while preserving correctness, respecting priority, preventing duplicate settlement, and resolving liquidity gridlocks?**

---

# 4. Main Objective

Build a student-manageable but technically serious backend demonstrating:

1. Java 21
2. Spring Boot
3. Java concurrency
4. Payment settlement algorithms
5. Liquidity management
6. Priority scheduling
7. Gridlock detection and resolution
8. PostgreSQL durability
9. Kafka event-driven ingestion
10. Redis hot-state optimization
11. Docker-based reproducibility
12. Idempotency and failure recovery
13. Load generation and real benchmark results

---

# 5. Most Important Implementation Principle

## CORE FIRST — INFRASTRUCTURE LATER

The project must **not** begin with Kafka, Redis, Docker, or distributed infrastructure.

The implementation must evolve in this order:

```text
Java + Spring Boot baseline
        |
        v
Core domain model
        |
        v
Single-threaded settlement logic
        |
        v
Java concurrency
        |
        v
Priority queue + liquidity scheduling
        |
        v
Gridlock detection
        |
        v
Gridlock resolution
        |
        v
PostgreSQL durability
        |
        v
Kafka event-driven ingestion
        |
        v
Redis optimization
        |
        v
Dockerization
        |
        v
Load testing + benchmarking
        |
        v
Failure/recovery testing
```

This order is mandatory.

---

# 6. Why This Order Matters

Before Kafka, the developer should already understand:

- how settlement works,
- how payments queue,
- how liquidity is protected,
- how priority scheduling works,
- how gridlock is detected and resolved.

Then each technology answers a clear problem.

**Kafka:** How do we make payment ingestion asynchronous and event-driven?

**Redis:** How do we speed up hot liquidity/queue/idempotency access without replacing durable truth?

**Docker:** How do we package the already-working components into a reproducible environment?

This is intentionally a learning-first implementation sequence.

---

# 7. Final Technology Stack

| Technology | Purpose |
|---|---|
| Java 21 | Core business logic, algorithms, concurrency |
| Spring Boot | Backend framework and REST API |
| Java Concurrency API | Safe concurrent payment processing |
| PostgreSQL | Durable source of truth |
| Apache Kafka | Asynchronous payment/event ingestion |
| Redis | Fast-changing liquidity/queue/idempotency hot state |
| Docker / Docker Compose | Reproducible local runtime |
| Maven | Build and dependency management |
| JUnit 5 | Automated testing |
| Testcontainers | Later-stage integration testing where useful |

---

# 8. Explicitly Out of Scope

Do not add:

- Kubernetes
- Grafana
- Prometheus
- OpenTelemetry
- service mesh
- frontend application
- machine learning
- blockchain
- multiple microservices
- cloud-specific infrastructure
- RabbitMQ
- MongoDB
- Elasticsearch
- full SWIFT integration
- full ISO 20022 implementation
- multiple currencies or FX
- complicated authentication
- extra technologies just for resume inflation

The system should remain one **modular Spring Boot backend**.

---

# 9. Runtime / Operating-System Assumptions

The project must not require Linux as the primary development OS.

Supported development environments should include:

```text
Windows
Windows + WSL
Linux
macOS
```

Early phases require only:

```text
Java 21
Maven
```

They must **not require Docker or WSL**.

Docker/WSL becomes relevant only when the Docker phase is reached.

## Coding-Agent Rule

If Docker Desktop, WSL, or Linux containers are unavailable during an early phase:

> **DO NOT BLOCK IMPLEMENTATION.**

Continue with the current non-Docker phase.

Do not redesign the architecture around missing Docker support.

---

# 10. Existing Early Docker Work

If the repository already contains work from the earlier attempt, such as:

```text
Dockerfile
docker-compose.yml
database migrations
dependency-check endpoint
Docker-related status notes
```

then:

- preserve correct work,
- do not delete it just because Docker is now later,
- do not require it for early-phase completion,
- mark it as dormant until the Docker phase,
- revisit and verify it when the Docker phase arrives.

Do not restart the repository from scratch unnecessarily.

---

# 11. Architectural Shape

The final project is one modular Spring Boot application.

```text
Payment Client / Load Generator
          |
          v
       REST API
          |
      later: Kafka
          |
          v
+------------------------------+
|      Spring Boot Backend     |
|                              |
| Payment Processor            |
| Liquidity Engine             |
| Priority Scheduler           |
| Gridlock Resolver            |
| Settlement Coordinator       |
+---------------+--------------+
                |
      +---------+---------+
      |                   |
      v                   v
 PostgreSQL           later: Redis
```

No unnecessary microservice decomposition.

---

# 12. Core Financial Invariants

These are release-blocking.

## INV-1 — No Negative Liquidity

```text
availableLiquidity >= 0
```

after every committed operation.

## INV-2 — Settle At Most Once

A payment can generate at most one committed settlement.

## INV-3 — Atomic Settlement

No half-settled financial state.

## INV-4 — Gridlock Batch Atomicity

```text
ALL COMMIT
or
NONE COMMIT
```

## INV-5 — Duplicate Processing Has Zero Financial Effect

Repeated processing must not move money twice.

## INV-6 — Deterministic Scheduling

Same state + same inputs should produce reproducible scheduling decisions.

---

# 13. Money Representation

Never use `float` or `double` for money.

Use:

```java
long amountMinor;
```

Example:

```text
12,345.67 -> 1,234,567 minor units
```

Later, PostgreSQL uses `BIGINT`.

---

# 14. Domain Model

## Participant

```text
participantId
displayName
```

## PaymentInstruction

```text
paymentId
sourceParticipantId
destinationParticipantId
amountMinor
priority
createdAt
deadline
status
queuedAt
settledAt
```

## PaymentPriority

```text
URGENT
HIGH
NORMAL
```

## PaymentStatus

```text
RECEIVED
QUEUED
SETTLED
REJECTED
FAILED
```

## LiquidityPosition

```text
participantId
availableMinor
```

## SettlementRecord

```text
settlementId
paymentId
sourceParticipantId
destinationParticipantId
amountMinor
settlementType
settledAt
batchId
```

Settlement types:

```text
IMMEDIATE
QUEUED
GRIDLOCK_BATCH
```

---

# 15. Recommended Package Evolution

Start small.

Early:

```text
payment/
liquidity/
settlement/
queue/
gridlock/
concurrency/
common/
```

Add only when needed:

```text
persistence/    # PostgreSQL phase
messaging/      # Kafka phase
redis/          # Redis phase
benchmark/      # benchmark phase
```

Do not create large empty architecture in advance.

---

# 16. Initial Implementation Mode

The early system should work **without Kafka, Redis, PostgreSQL, or Docker**.

Use in-memory stores to prove business logic:

```text
ConcurrentHashMap<String, PaymentInstruction>
ConcurrentHashMap<String, LiquidityPosition>
in-memory settlement storage
in-memory queue
```

These are temporary implementations.

Avoid tightly coupling settlement algorithms to them so PostgreSQL can replace them later.

Do not overengineer repository abstractions.

---

# 17. COMPLETE IMPLEMENTATION PHASES

The coding agent must complete these phases in order.

---

## PHASE 0 — Java + Maven + Spring Boot Baseline

### Goal

Get the simplest Spring Boot application running.

### Required

- Java 21
- Maven
- Spring Boot
- basic health endpoint
- clean package layout
- JUnit setup

### Explicitly Not Required

- PostgreSQL
- Kafka
- Redis
- Docker
- WSL

### Verification

```bash
mvn clean test
mvn spring-boot:run
```

### Exit Criteria

- application starts locally,
- tests run,
- no external infrastructure required.

---

## PHASE 1 — Core Domain Model

### Goal

Represent the financial problem correctly.

Implement:

```text
Participant
PaymentInstruction
PaymentPriority
PaymentStatus
LiquidityPosition
SettlementRecord
SettlementType
```

Validation must reject:

```text
amount <= 0
source == destination
unknown participant
invalid priority
```

### Tests

Test domain validation thoroughly.

### Exit Criteria

Core model is stable and tested.

---

## PHASE 2 — Basic Settlement Engine — Single Threaded

### Goal

Implement settlement logic before concurrency.

Use in-memory state.

```text
submit payment
      |
      v
check source liquidity
      |
  +---+---+
  |       |
enough   insufficient
  |       |
  v       v
SETTLE   QUEUE
```

Implement:

```text
PaymentService
LiquidityService
SettlementCoordinator
```

Example:

```text
A = 100
B = 20
A -> B = 40
```

Expected:

```text
A = 60
B = 60
payment = SETTLED
```

Insufficient case:

```text
A = 30
A -> B = 50
```

Expected:

```text
payment = QUEUED
A remains 30
```

### Tests

- successful settlement
- insufficient liquidity
- exact-liquidity settlement
- invalid payment
- duplicate direct processing

### Exit Criteria

Single-threaded financial flow works correctly.

---

## PHASE 3 — Java Concurrency and Correctness

### Goal

Make the settlement engine safe under concurrency.

Implement a participant lock manager, for example:

```text
ConcurrentHashMap<String, ReentrantLock>
```

### Race Example

```text
A opening liquidity = 100

10 concurrent payments
each = 30
```

The system must never allow:

```text
A < 0
```

### Locking Rule

For operations touching multiple participants:

1. collect IDs,
2. sort IDs,
3. acquire locks in sorted order,
4. execute operation,
5. release locks in reverse order.

### Required Tests

**CT-1 Oversubscription**  
Many same-source concurrent payments.

**CT-2 Duplicate Payment Race**  
Same payment processed by multiple threads.

**CT-3 Opposing Payments**  
A->B and B->A concurrently; no deadlock.

**CT-4 Repeated Stress**  
Run concurrency scenarios many times.

### Exit Criteria

Prove:

```text
liquidity never negative
payment never settles twice
locks do not deadlock
```

No Kafka, Redis, PostgreSQL, or Docker yet.

---

## PHASE 4 — Priority Queue and Liquidity Scheduling

### Goal

Introduce meaningful RTGS queue behavior.

Priority:

```text
URGENT
HIGH
NORMAL
```

Tie-breaking:

1. priority
2. deadline
3. queuedAt
4. paymentId

Implement:

```text
QueuedPaymentComparator
SettlementQueue
QueueScheduler
```

At this phase, queue state may remain in memory.

### Liquidity Injection

Add:

```text
POST /api/liquidity/{participantId}/inject
```

Flow:

```text
payment queued
    |
liquidity injected
    |
scheduler rechecks queue
    |
payment may settle
```

### Tests

- urgent beats high
- high beats normal
- same-priority FIFO
- deadline preference
- deterministic tie-breaking
- scheduler stops when no progress occurs

### Exit Criteria

Priority-based queued settlement works.

---

## PHASE 5 — Gridlock Detection

### Goal

Build the graph algorithm without Kafka/Redis/DB concerns.

Queued obligations:

```text
participant = vertex
payment = directed edge
amount = weight
```

Implement:

```text
PaymentEdge
ObligationGraph
StronglyConnectedComponents
GridlockCandidate
GridlockDetector
```

Use Tarjan's SCC algorithm or equivalent deterministic SCC algorithm.

### Required Tests

```text
no cycle
2-node cycle
3-node cycle
multiple SCCs
disconnected graph
parallel edges
```

### Exit Criteria

Gridlock candidates are detected deterministically.

---

## PHASE 6 — Gridlock Resolution

### Goal

Implement the project's primary algorithmic differentiator.

For a candidate batch:

```text
projected final liquidity =
starting liquidity
+ batch incoming
- batch outgoing
```

A batch is feasible only if every participant has:

```text
projected liquidity >= 0
```

Implement:

```text
GridlockResolver
ProjectedLiquidityCalculator
GridlockResolutionResult
```

### Candidate Policy

Prefer:

```text
URGENT
HIGH
NORMAL
```

If full SCC is not feasible:

- deterministically remove lower-priority / least-useful obligations,
- recompute,
- continue until feasible or no useful batch remains.

Document the exact pruning rule.

### Atomicity in Memory

Even before PostgreSQL:

- acquire all participant locks in sorted order,
- revalidate state,
- apply the entire batch,
- otherwise apply none.

### Tests

- feasible 3-node cycle
- infeasible cycle
- mixed priorities
- state change between detection and resolution
- concurrent normal settlement vs gridlock batch

### Exit Criteria

Core gridlock functionality works.

---

# 18. CORE MILESTONE

Before adding PostgreSQL/Kafka/Redis/Docker, the project must already demonstrate:

```text
Java 21
Spring Boot
Payment settlement
Liquidity management
Java concurrency
Priority scheduling
Gridlock detection
Gridlock resolution
```

The developer should be able to explain the whole core system without mentioning scalability infrastructure.

---

## PHASE 7 — PostgreSQL Persistence

### Goal

Replace temporary in-memory durable state with PostgreSQL.

PostgreSQL becomes the final source of truth.

Required durable tables:

```text
participants
liquidity_positions
payments
settlements
processing_failures
```

### Minimum Schema Guidance

`participants`

```sql
participant_id VARCHAR(64) PRIMARY KEY,
display_name VARCHAR(128) NOT NULL
```

`liquidity_positions`

```sql
participant_id VARCHAR(64) PRIMARY KEY,
available_minor BIGINT NOT NULL CHECK (available_minor >= 0),
version BIGINT NOT NULL DEFAULT 0,
updated_at TIMESTAMPTZ NOT NULL
```

`payments`

```sql
payment_id VARCHAR(64) PRIMARY KEY,
source_participant_id VARCHAR(64) NOT NULL,
destination_participant_id VARCHAR(64) NOT NULL,
amount_minor BIGINT NOT NULL CHECK (amount_minor > 0),
priority VARCHAR(16) NOT NULL,
status VARCHAR(16) NOT NULL,
created_at TIMESTAMPTZ NOT NULL,
deadline TIMESTAMPTZ NULL,
queued_at TIMESTAMPTZ NULL,
settled_at TIMESTAMPTZ NULL,
version BIGINT NOT NULL DEFAULT 0
```

`settlements`

```sql
settlement_id UUID PRIMARY KEY,
payment_id VARCHAR(64) NOT NULL UNIQUE,
source_participant_id VARCHAR(64) NOT NULL,
destination_participant_id VARCHAR(64) NOT NULL,
amount_minor BIGINT NOT NULL,
settlement_type VARCHAR(32) NOT NULL,
batch_id UUID NULL,
settled_at TIMESTAMPTZ NOT NULL
```

The `UNIQUE(payment_id)` settlement constraint is mandatory.

### Transaction Rule

Once PostgreSQL exists:

1. acquire Java participant locks,
2. begin DB transaction,
3. lock/reload durable rows,
4. revalidate payment,
5. verify durable liquidity,
6. apply debit/credit,
7. insert settlement,
8. mark payment SETTLED,
9. commit,
10. release locks.

### Gridlock Batch

- sort participant IDs
- acquire locks
- begin one DB transaction
- re-read all candidate payment/balance state
- recompute feasibility
- apply all effects
- commit once

### Tests

- negative balance prohibited
- unique settlement enforced
- rollback works
- gridlock batch atomicity
- application restart retains state

### Exit Criteria

The core system survives application restart with correct durable state.

---

## PHASE 8 — Kafka Event-Driven Ingestion

### Goal

Only after synchronous settlement is correct, make ingestion asynchronous.

Before:

```text
REST -> PaymentService -> Settlement
```

After:

```text
REST -> Kafka -> Consumer -> PaymentService -> Settlement
```

Kafka does not replace business logic.

### Topics

```text
payment.incoming
payment.dlq
```

Optional:

```text
settlement.completed
```

Avoid topic explosion.

### Partition Key

Use:

```text
sourceParticipantId
```

unless a better design is explicitly justified.

### Semantics

Assume at-least-once delivery.

### Idempotency

Use durable defenses:

```text
payment status
+
settlements.payment_id UNIQUE
+
transaction revalidation
```

Kafka ordering is not the only correctness mechanism.

### Tests

- REST accepts payment
- event published
- consumer processes payment
- duplicate event harmless
- retries bounded
- DLQ path works

### Exit Criteria

Event-driven ingestion works without weakening settlement correctness.

---

## PHASE 9 — Redis Optimization Layer

### Goal

Add Redis only after PostgreSQL and Kafka work.

Redis is for acceleration, not truth.

Use Redis for:

```text
liquidity cache
priority queue index
settled-payment fast lookup
temporary operational state
```

Suggested keys:

```text
rtgs:liquidity:{participantId}
rtgs:queue
rtgs:settled:{paymentId}
```

### Critical Rule

Correct order:

```text
PostgreSQL transaction commits
            |
            v
Redis updated
```

Never make Redis the only durable financial state.

### Recovery

Implement `RedisRecoveryService`.

It should:

1. reload liquidity from PostgreSQL,
2. rebuild queued payments,
3. rebuild/lazily restore settled cache.

### Tests

- clear Redis
- rebuild
- compare with DB
- duplicate event still harmless
- Redis failure cannot create false settlement

### Exit Criteria

Redis improves performance without becoming required for durable correctness.

---

## PHASE 10 — Dockerization

### Goal

Only now package the completed components.

Docker should run:

```text
PostgreSQL
Kafka
Redis
Spring Boot application
```

Use Docker Compose.

Prefer Kafka KRaft mode.

### Windows / WSL Note

On Windows, Docker Desktop may use WSL 2 for Linux containers.

This is an environment requirement of Docker Desktop, not a requirement of the Java project.

If WSL/Docker is unavailable:

- mark only the Docker runtime verification as blocked,
- do not invalidate previous phases,
- do not redesign the core system.

### Existing Docker Files

If Docker work was created earlier:

- inspect it,
- preserve correct configuration,
- update only what the final system requires.

### Verification

```bash
docker compose config --quiet
docker compose up -d --build
docker compose ps
```

Verify:

- PostgreSQL reachable
- Kafka reachable
- Redis reachable
- Spring Boot starts
- migrations run
- full payment flow works

### Exit Criteria

Full environment starts reproducibly.

---

## PHASE 11 — Deterministic Load Generator

### Goal

Generate serious load without another major platform.

Implement a Java workload generator.

Configurable:

```text
payment count
target rate/sec
participant count
opening liquidity
amount distribution
priority distribution
deadline rate
duplicate rate
burst behavior
random seed
```

Same seed must generate the same logical workload.

Profiles:

```text
normal-day
peak-day
burst-day
liquidity-constrained
gridlock-heavy
duplicate-heavy
```

If public payment-system statistics are used, label numbers as:

```text
PUBLICLY SOURCED
DERIVED
MODELED ASSUMPTION
```

Never describe synthetic data as a private bank production trace.

---

## PHASE 12 — Benchmarking

Two categories are required.

### A. End-to-End Benchmark

Measure:

```text
Generator
 -> Kafka
 -> Spring Boot
 -> PostgreSQL
 -> Redis
```

Collect:

```text
payments/sec
settlements/sec
p50 latency
p95 latency
p99 latency
queue depth
failures
duplicate financial effects
total duration
backlog recovery time
```

### B. Algorithm Benchmark

Compare:

```text
FIFO
PRIORITY
PRIORITY_PLUS_GRIDLOCK
```

Use the exact same workload.

Measure:

```text
settled count
settled value
average queue wait
unresolved payments
gridlock batches
opening liquidity required
```

### Liquidity Reduction Metric

For the same workload and success target:

1. find minimum opening liquidity for FIFO,
2. find minimum opening liquidity for optimized scheduler,
3. calculate:

```text
reduction =
(baselineMinimum - optimizedMinimum)
/
baselineMinimum
* 100
```

Only use measured percentages on the resume.

---

## PHASE 13 — Failure and Recovery Testing

Test:

```text
duplicate Kafka delivery
consumer restart
Redis data deletion
temporary Redis outage
PostgreSQL transaction failure
application restart
stale gridlock candidate
concurrent normal settlement during gridlock work
```

Verify:

```text
no duplicate settlement
no negative liquidity
recoverable Redis state
no fake committed payment
```

---

## PHASE 14 — Final Documentation

Required:

```text
README.md
docs/architecture.md
docs/concurrency.md
docs/gridlock-algorithm.md
docs/kafka-semantics.md
docs/redis-role.md
docs/benchmark-methodology.md
docs/implementation-status.md
```

README should explain the project in learning order:

```text
1. problem
2. basic settlement
3. concurrency
4. priority scheduling
5. gridlock
6. PostgreSQL
7. Kafka
8. Redis
9. Docker
10. benchmarking
```

---

# 19. API Requirements

Final recommended API:

```text
POST /api/payments
GET  /api/payments/{paymentId}
GET  /api/payments?status=QUEUED

GET  /api/liquidity/{participantId}
POST /api/liquidity/{participantId}/inject

GET  /api/settlements/{paymentId}

POST /api/scheduler/run
POST /api/gridlock/run

POST /api/admin/redis/rebuild

GET /api/stats
```

Add endpoints only when the corresponding subsystem exists.

---

# 20. Concurrency Rules

Concurrency remains a centerpiece.

For any multi-participant operation:

```text
collect IDs
sort IDs
acquire locks in sorted order
perform operation
release in reverse order
```

Once PostgreSQL exists, Java locks reduce local contention but database transactions and constraints remain the final correctness boundary.

---

# 21. Gridlock Algorithm Requirement

Do not reduce gridlock handling to:

```text
cycle exists -> settle everything
```

Required flow:

```text
build graph
detect SCC
construct candidate set
calculate projected balances
reject negative projected balance
prune deterministically if needed
lock affected participants
re-read latest durable state
recompute feasibility
commit valid batch atomically
```

---

# 22. Idempotency Requirement

Once Kafka exists:

```text
same payment event delivered twice
```

must produce:

```text
1 settlement
0 duplicate financial effect
```

Layered defense:

```text
Redis fast hint (later)
+
durable payment state
+
unique settlement constraint
+
transactional revalidation
```

Redis alone is not idempotency.

---

# 23. Redis Responsibility

Redis should make the project faster, not more fragile.

Use it for:

```text
hot liquidity reads
queue indexing
fast duplicate lookup
temporary runtime state
```

Never require Redis to reconstruct a financial fact PostgreSQL never stored.

---

# 24. PostgreSQL Responsibility

PostgreSQL owns:

```text
payments
settlement records
durable liquidity
queued status
persistent failures
```

If Redis and PostgreSQL disagree:

```text
PostgreSQL wins.
```

---

# 25. Kafka Responsibility

Kafka owns event transport, not final financial state.

Its role:

```text
decouple submission
buffer bursts
enable asynchronous processing
support replay/redelivery
support scalable consumers
```

---

# 26. Docker Responsibility

Docker provides packaging and reproducibility.

Its role:

```text
same environment
same dependency versions
easy startup
repeatable benchmark setup
```

Docker is deliberately near the end.

---

# 27. Testing Requirements by Layer

## Core Phases

Use ordinary JUnit. No infrastructure required.

Test:

```text
domain
settlement
concurrency
priority queue
graph
gridlock
```

## PostgreSQL Phase

Add database integration tests.

## Kafka Phase

Add event/idempotency/DLQ tests.

## Redis Phase

Add cache/rebuild/failure tests.

## Docker Phase

Add full runtime verification.

Do not force Testcontainers into early learning phases if unit tests are enough.

---

# 28. Phase Gate Reporting

After every phase, the coding agent must report:

```text
PHASE N COMPLETE / FAIL

Files created:
- ...

Files modified:
- ...

Implemented:
- ...

Tests added:
- ...

Commands run:
- ...

Results:
- ...

Exit criteria:
PASS / FAIL

Known limitations:
- ...
```

If a phase is blocked by a dependency that belongs to a later phase, the current phase design is wrong.

---

# 29. Special Docker / WSL Rule

An earlier implementation attempt marked Phase 1 failed because Docker Desktop's Linux engine / WSL was unavailable.

Under this revised plan:

```text
Docker unavailable during Phases 0-9
```

is **not a project failure**.

Docker availability matters only in Phase 10.

The coding agent must never move Docker earlier merely because Docker files already exist.

---

# 30. Expected LOC Budget

Target:

```text
Core Java/Spring logic:        3,500-5,000
Persistence/Kafka/Redis:       1,500-2,500
Tests:                         1,500-2,500
Benchmark/load utilities:      500-1,000
Config/scripts:                200-500
------------------------------------------------
Expected meaningful total:     ~7,000-10,000 LOC
```

This is not a quota.

If the project grows beyond ~10-12K meaningful LOC, review for:

- scope creep
- unnecessary abstractions
- duplicated layers
- premature microservices
- infrastructure bloat

---

# 31. Definition of Done

## Core

- [ ] Java 21 application works.
- [ ] Spring Boot API works.
- [ ] Domain model works.
- [ ] Immediate settlement works.
- [ ] Insufficient liquidity queues payment.
- [ ] Liquidity injection works.

## Concurrency

- [ ] No negative balance under concurrent load.
- [ ] No duplicate settlement.
- [ ] Lock ordering avoids deadlocks.
- [ ] Gridlock resolution is safe against concurrent settlement.

## Scheduling

- [ ] URGENT > HIGH > NORMAL.
- [ ] deterministic tie-breaking works.
- [ ] queued payments re-evaluate when liquidity changes.

## Gridlock

- [ ] graph builds correctly.
- [ ] SCC detection works.
- [ ] feasible group settlement works.
- [ ] infeasible group is rejected.
- [ ] pruning is deterministic.
- [ ] batch is atomic.

## PostgreSQL

- [ ] durable payment state works.
- [ ] durable liquidity works.
- [ ] unique settlement constraint works.
- [ ] rollback works.
- [ ] restart retains state.

## Kafka

- [ ] async ingestion works.
- [ ] retries bounded.
- [ ] duplicate delivery harmless.
- [ ] DLQ exists.

## Redis

- [ ] liquidity cache works.
- [ ] queue index works.
- [ ] settled lookup works.
- [ ] Redis rebuild works.
- [ ] clearing Redis does not destroy financial truth.

## Docker

- [ ] Compose starts PostgreSQL.
- [ ] Compose starts Kafka.
- [ ] Compose starts Redis.
- [ ] Compose starts Spring Boot.
- [ ] end-to-end flow works in containers.

## Benchmarking

- [ ] deterministic generator exists.
- [ ] FIFO benchmark exists.
- [ ] PRIORITY benchmark exists.
- [ ] PRIORITY_PLUS_GRIDLOCK benchmark exists.
- [ ] end-to-end load benchmark exists.
- [ ] result files are stored.
- [ ] no metric is fabricated.

## Documentation

- [ ] architecture explained.
- [ ] concurrency explained.
- [ ] gridlock explained.
- [ ] Kafka role explained.
- [ ] Redis role explained.
- [ ] PostgreSQL authority explained.
- [ ] benchmark methodology explained.

---

# 32. What Must Work Before Kafka / Redis / Docker

Before scalability/infrastructure layers are introduced, demonstrate:

### Demo 1 — Basic Settlement

```text
A=100
B=0
A->B=40
```

Expected:

```text
A=60
B=40
SETTLED
```

### Demo 2 — Queue

```text
A=20
A->B=50
```

Expected:

```text
QUEUED
```

Inject 40.

Expected:

```text
SETTLED
```

### Demo 3 — Priority

Normal and urgent payments compete.

Urgent wins.

### Demo 4 — Concurrency

Many same-source payments arrive concurrently.

No negative balance and no duplicate settlement.

### Demo 5 — Gridlock

A blocked cycle exists.

The resolver finds and commits a feasible atomic batch.

Only after these work should scalability layers be introduced.

---

# 33. Resume-Oriented Metrics to Collect Later

Possible final metrics:

```text
Processed X payments/sec at Y ms p99.
```

```text
Handled N duplicate/redelivered events with zero duplicate settlement effects.
```

```text
Reduced simulated minimum opening liquidity by X% versus FIFO.
```

```text
Processed N concurrent payment attempts with zero negative-liquidity violations.
```

```text
Recovered an N-event backlog in X seconds after consumer restart.
```

Never populate these before measurement.

---

# 34. Instructions to the Coding LLM

1. Read this document completely.
2. Inspect the existing repository.
3. Preserve correct work.
4. Reorder implementation according to this revised phase sequence.
5. Do not require Docker before Phase 10.
6. Do not require Redis before Phase 9.
7. Do not require Kafka before Phase 8.
8. Do not require PostgreSQL before Phase 7.
9. Build core business logic first.
10. Test single-threaded correctness before concurrency.
11. Test concurrency before infrastructure.
12. Treat concurrency and gridlock correctness as release blockers.
13. PostgreSQL becomes authoritative once introduced.
14. Redis remains rebuildable.
15. Kafka remains transport, not financial truth.
16. Docker remains packaging/infrastructure.
17. Never fabricate benchmark results.
18. Do not add prohibited technologies.
19. Keep the codebase understandable by a third/fourth-year student.
20. Do not skip phases because later infrastructure already exists.
21. Existing Docker work should remain dormant until the Docker phase.
22. If WSL/Docker is unavailable before Phase 10, continue normally.
23. The project is complete only when all Definition-of-Done items are verified.

---

# 35. Current Repository Recovery Instruction

Because an earlier version of the plan placed Docker too early, the current repository may already contain Docker-related work.

The coding agent should do this:

```text
1. Inspect existing Phase 0/1 work.
2. Preserve correct Spring Boot/Maven setup.
3. Preserve valid Docker/Compose/migration files without making them active requirements.
4. Update implementation-status.md to state that the phase order has changed.
5. Begin/re-enter the revised Phase 1 domain implementation.
6. Do not spend time solving WSL/Docker until Phase 10 unless the developer independently wants Docker working sooner.
```

Do not throw away working code purely because the phase order changed.

---

# 36. Final Implementation Philosophy

The desired journey is:

```text
Understand the banking problem
        ->
implement the basic algorithm
        ->
prove correctness
        ->
add concurrency
        ->
prove concurrency
        ->
add scheduling
        ->
solve gridlocks
        ->
make state durable
        ->
make ingestion asynchronous
        ->
make hot state faster
        ->
containerize
        ->
load test
        ->
measure
```

Not:

```text
install infrastructure
        ->
debug containers
        ->
add more technology
        ->
eventually write the actual system
```

The finished project should prove that the developer understands:

```text
WHAT the system does
```

and:

```text
WHY every technology was added.
```

That is the central goal of this revised handoff.
