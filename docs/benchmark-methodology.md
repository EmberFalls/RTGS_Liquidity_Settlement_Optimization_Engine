# Benchmark methodology

Every profile is **MODELED ASSUMPTION**. No profile represents a private production trace or publicly sourced traffic statistic. `paymentCount` is the number of attempts, including generated duplicates. A fixed seed, profile, and configuration reproduce the logical instructions, IDs, timestamps, and scheduled offsets. Runtime namespace prefixes isolate repeated end-to-end runs without changing logical workload content.

Configurable fields include target rate, participant count, opening liquidity, uniform/bimodal amounts, priority probabilities, deadline/duplicate probabilities, burst size, and seed. Money stays in integer minor units; floating point is used only for probabilities and statistics.

## Algorithm comparison

FIFO, PRIORITY, and PRIORITY_PLUS_GRIDLOCK receive identical logical workloads. Every instruction enters the queue before scheduling. FIFO stops at an unfunded head; priority scans ordered queued instructions for a feasible payment. The optimized strategy also resolves SCC batches. These policy choices are part of the comparison, not a claim that every real RTGS system uses strict global FIFO.

The six profiles each run 300 attempts with seed 42. Metrics are settled count/value, simulated queue wait, unresolved instructions, and gridlock batches. Queue wait uses the workload's simulation clock; it is not measured wall-clock latency. Opening liquidity is reported per participant.

For the success-target comparison, a separate 120-attempt, three-participant workload with amounts 10–20 is used. Exhaustive integer search finds the first opening amount that settles 100% of unique instructions. No monotonicity assumption is needed. The stored run found FIFO=238, PRIORITY=187, and PRIORITY_PLUS_GRIDLOCK=187 minor units per participant: 21.43% reduction versus this FIFO policy. In that run, gridlock did not improve the minimum beyond priority alone.

## End-to-end measurement

The Java client submits normal-day requests at a 100/sec target to REST, which publishes Kafka events; Spring settles in PostgreSQL and refreshes Redis. A fresh namespace isolates database measurements. The benchmark polls durable state, checks settlement uniqueness and liquidity conservation, and verifies a Redis settled hint.

Latency is client-send wall-clock time to PostgreSQL `settled_at`, assuming synchronized clocks in this same-host Docker environment. Percentiles use nearest-rank over unique settled payments. Throughput divides attempts/settlements by total measured duration, including final backlog drain. The benchmark process uses UTC for JDBC compatibility. Recovery time in this result means drain after the last accepted submission; restart recovery is reported separately by the recovery script.

Results include workload configuration, runtime Java/OS/CPU count, failure count, queue depth, duplicate financial effects, and invariants. JSON files are in `docs/benchmark-results`. The tested application image identifier is stored beside them. These are small single-host measurements at a configured target rate, not saturation tests or broad capacity claims.

## Recovery timing

The native recovery script stops the application, publishes 120 records for 100 unique instructions, starts the application, and measures until the final durable settlement is observed. Timing includes application startup after the Compose start operation returns. It validates balances before and after an additional application restart, Redis deletion/rebuild, Redis outage, and an injected PostgreSQL settlement failure. Raw results are in `docs/verification/failure-recovery.json`.
