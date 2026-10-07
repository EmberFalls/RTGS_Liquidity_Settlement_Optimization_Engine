package com.rtgs.gridlock;

import com.rtgs.concurrency.ParticipantLockManager;
import com.rtgs.liquidity.LiquidityService;
import com.rtgs.payment.PaymentInstruction;
import com.rtgs.payment.PaymentService;
import com.rtgs.payment.PaymentStatus;
import com.rtgs.settlement.SettlementRecord;
import com.rtgs.settlement.SettlementType;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

/** Plans and commits a simultaneous net settlement while holding every affected participant lock. */
public class GridlockResolver {
    private final PaymentService payments;
    private final LiquidityService liquidity;
    private final ParticipantLockManager locks;
    private final Clock clock;
    private final Map<String, SettlementRecord> settlements;
    private final ProjectedLiquidityCalculator calculator = new ProjectedLiquidityCalculator();
    private final GridlockDetector detector = new GridlockDetector();

    public GridlockResolver(PaymentService payments, LiquidityService liquidity, ParticipantLockManager locks,
                            Clock clock, Map<String, SettlementRecord> settlements) {
        this.payments = payments;
        this.liquidity = liquidity;
        this.locks = locks;
        this.clock = clock;
        this.settlements = settlements;
    }

    public GridlockResolutionResult resolve(GridlockCandidate candidate) {
        return locks.withLocks(candidate.participants(), () -> resolveLocked(candidate));
    }

    private GridlockResolutionResult resolveLocked(GridlockCandidate candidate) {
        List<PaymentInstruction> remaining = new ArrayList<>();
        for (PaymentEdge edge : candidate.edges()) {
            PaymentInstruction current = payments.require(edge.paymentId());
            if (current.status() != PaymentStatus.QUEUED || !current.sourceParticipantId().equals(edge.sourceParticipantId())
                    || !current.destinationParticipantId().equals(edge.destinationParticipantId())
                    || current.amountMinor() != edge.amountMinor())
                return rejected(List.of(), "candidate changed since detection");
            if (settlements.containsKey(edge.paymentId())) return rejected(List.of(), "payment already settled");
            remaining.add(current);
        }

        List<String> pruned = new ArrayList<>();
        while (remaining.size() >= 2) {
            List<GridlockCandidate> cycles = detector.detect(remaining);
            if (cycles.isEmpty()) return rejected(pruned, "no cycle remains");
            GridlockCandidate cycle = cycles.getFirst();
            Set<String> cycleIds = new HashSet<>();
            cycle.edges().forEach(edge -> cycleIds.add(edge.paymentId()));
            List<PaymentInstruction> batch = remaining.stream().filter(p -> cycleIds.contains(p.paymentId())).toList();
            Map<String, Long> projected = calculator.calculate(batch, liquidity);
            if (calculator.feasible(projected)) return commit(batch, projected, pruned);

            // Deterministic pruning: lowest priority first, then largest amount,
            // then lexicographically greatest paymentId. Re-detect SCCs after each removal.
            PaymentInstruction remove = batch.stream().max(Comparator
                    .comparingInt((PaymentInstruction p) -> p.priority().ordinal())
                    .thenComparingLong(PaymentInstruction::amountMinor)
                    .thenComparing(PaymentInstruction::paymentId)).orElseThrow();
            remaining.remove(remove);
            pruned.add(remove.paymentId());
        }
        return rejected(pruned, "no feasible cycle remains");
    }

    private GridlockResolutionResult commit(List<PaymentInstruction> batch, Map<String, Long> projected,
                                            List<String> pruned) {
        UUID batchId = UUID.randomUUID();
        Instant at = clock.instant();
        Map<String, SettlementRecord> preparedRecords = new LinkedHashMap<>();
        Map<String, PaymentInstruction> preparedPayments = new LinkedHashMap<>();
        for (PaymentInstruction payment : batch) {
            preparedRecords.put(payment.paymentId(), new SettlementRecord(UUID.randomUUID(), payment.paymentId(),
                    payment.sourceParticipantId(), payment.destinationParticipantId(), payment.amountMinor(),
                    SettlementType.GRIDLOCK_BATCH, at, batchId));
            preparedPayments.put(payment.paymentId(), payment.settled(at));
        }
        // All arithmetic, validation, and object creation finish before the first mutation.
        liquidity.applyProjected(projected);
        preparedRecords.forEach(settlements::put);
        preparedPayments.values().forEach(payments::replace);
        return new GridlockResolutionResult(true, List.copyOf(preparedPayments.keySet()), pruned, batchId, "committed");
    }

    private GridlockResolutionResult rejected(List<String> pruned, String reason) {
        return new GridlockResolutionResult(false, List.of(), pruned, null, reason);
    }
}
