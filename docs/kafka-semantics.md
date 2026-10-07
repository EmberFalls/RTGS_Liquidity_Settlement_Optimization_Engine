# Kafka semantics

With profiles `postgres,kafka`, REST serializes a received instruction to `payment.incoming`, keyed by source participant ID. Broker acknowledgement is required before REST returns. The consumer performs the same durable synchronous financial operation used without Kafka. Topics have three partitions and one replica in the local demonstration setup.

Delivery is at least once. Ordering within a source partition reduces contention, but correctness depends on durable status, transaction revalidation, and `settlements.payment_id UNIQUE`. A duplicate event can generate only one financial effect. Redis may hint that the payment settled; PostgreSQL confirms that fact before returning early.

The incoming consumer has two retries after the first attempt, using 100 ms backoff, then sends the original failed payload to `payment.dlq` in the corresponding partition. A separate recorder writes durable processing failures. If the recorder cannot persist a DLQ record, its container stops and retains the unacknowledged Kafka record. After database recovery, restarting the application/recorder replays it. It never republishes to its own DLQ.

PostgreSQL commit and Kafka offset acknowledgement are separate operations. A crash between them can replay an event; durable idempotency makes replay harmless. This application does not claim distributed exactly-once processing.

Embedded Kafka tests verify REST publication, consumer settlement, duplicate delivery, bounded incoming retries, DLQ routing, and DLQ persistence failure/replay. The native recovery script publishes 120 records for 100 unique payments with the application stopped, then verifies balances after consumer restart.
