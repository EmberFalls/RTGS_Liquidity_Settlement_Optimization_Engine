# Redis role and recovery

Redis stores rebuildable advisory state:

| Key | Role |
| --- | --- |
| `rtgs:liquidity:{participantId}` | Committed balance snapshot with 5-minute TTL |
| `rtgs:queue` | Ranked payment-ID index built from durable priority ordering |
| `rtgs:settled:{paymentId}` | Settled hint with 24-hour TTL |

Financial decisions and public authoritative balance reads use PostgreSQL. A positive settled hint first loads and validates the durable payment. It can skip a new settlement transaction only when durable status is already SETTLED. A forged or stale hint cannot settle a payment or prevent a new ID from being processed.

Cache refresh runs after PostgreSQL transaction completion. Failed refresh/read operations mark the cache unhealthy and fall back to durable processing. They never roll back an already committed payment or report a cache write as a financial commit.

`RedisRecoveryService` reloads liquidity, rebuilds the queue index using a temporary key and rename, and restores settled hints. `POST /api/admin/redis/rebuild` explicitly requests recovery. Rebuilds are serialized within the application. Cache indexes are operational views; the scheduler still revalidates durable queue membership.

Protocol-server tests clear RTGS keys, rebuild, compare values with PostgreSQL, inject a false settled hint, and stop Redis. Native Compose tests also clear Redis and settle payments during an outage. Cache speedup has not been separately isolated in a benchmark; stored performance results measure the complete enabled pipeline.
