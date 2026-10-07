# Concurrency and correctness

`ParticipantLockManager` keeps a `ConcurrentHashMap<String, ReentrantLock>`. A payment touches the source and destination. A gridlock batch touches all candidate participants. IDs are deduplicated and sorted before lock acquisition; release is in reverse order inside `finally`.

Two threads processing the same payment acquire the same participant locks and then re-read status. A settled instruction generates no additional financial movement. Conflicting financial content under the same payment ID is rejected. Opposing A→B/B→A operations use the same lock order.

In memory, all batch arithmetic and settlement objects are prepared before mutation. Operations using the coordinator's shared lock manager see the complete batch before acquiring any affected participant lock. In-memory maps are temporary; arbitrary multi-map diagnostic reads are not a database snapshot.

In PostgreSQL, Java locks are acquired before entering a transaction. Payment rows are reloaded for update, participant liquidity rows are locked in sorted order, and liquidity is checked again. Debit, credit, settlement insertion, and status change commit together. A unique settlement constraint and a nonnegative liquidity check remain effective even with independent Java lock managers.

Gridlock candidates are revalidated under the full participant lock set. A candidate whose payment settled since detection is rejected. Current balances are used for a fresh feasibility calculation. An injected failure while inserting a batch settlement rolls back every balance and status update.

Evidence:

- CT-1: repeated same-source oversubscription settles only available liquidity.
- CT-2: duplicate races generate one settlement.
- CT-3: opposing payments complete within bounded futures.
- CT-4: repeated concurrent stress preserves total liquidity and settlement counts.
- GridlockResolverTest races normal settlement against a batch 30 times.
- PostgresConcurrencyTest uses two engine instances with independent Java locks.
- PostgreSQL trigger tests verify single-operation and batch rollback.

The synchronous scheduler serializes its own runs. It stops after a complete pass without progress; it does not spin waiting for new liquidity. Incoming committed credits and explicit injection trigger a new run.
