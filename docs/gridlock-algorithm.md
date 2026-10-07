# Gridlock algorithm

Queued payments form a directed multigraph. Participants are vertices; payments are weighted edges. Parallel edges retain separate payment IDs. Sorted vertices and payment IDs make traversal and candidate ordering deterministic. Tarjan's SCC algorithm detects components with at least two participants.

For each candidate, the resolver acquires every participant lock in sorted order. It reloads payment states and current positions. For PostgreSQL this happens in one transaction with row locks. A changed or already settled candidate is rejected without effects.

For each participant:

```text
projected = opening liquidity + batch incoming - batch outgoing
```

Every projected final balance must be nonnegative and representable by `long`. An individually unfunded edge may participate in a feasible simultaneous batch; no sequential intermediate debit is committed.

If the full candidate is infeasible, remove exactly one obligation using this order:

1. Lowest priority first: NORMAL, then HIGH, then URGENT.
2. Within a priority, largest amount first.
3. For an equal amount, lexicographically greatest payment ID first.

Rebuild SCCs after each removal. Choose the first remaining component in participant-ID order, recalculate, and continue until a feasible cycle is found or no cycle remains. Removed obligations stay queued. This is a deterministic heuristic, not a globally optimal subset search; it may leave a feasible alternative unexplored.

On success, construct one batch ID, prepare every settlement record, apply final projected positions, insert records, and mark payments settled. PostgreSQL commits once. Any transaction failure commits none. Queue scheduling then runs again to use released liquidity.

Tests cover acyclic graphs, two/three-node cycles, disconnected/multiple SCCs, parallel edges, feasible/infeasible batches, mixed priority pruning, stale candidates, and concurrent ordinary settlement. Native database triggers verify batch rollback.
