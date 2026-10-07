package com.rtgs.benchmark;

import com.rtgs.concurrency.ParticipantLockManager;
import com.rtgs.gridlock.*;
import com.rtgs.liquidity.LiquidityService;
import com.rtgs.payment.*;
import com.rtgs.queue.QueuedPaymentComparator;
import com.rtgs.settlement.SettlementCoordinator;
import java.time.*;
import java.util.*;

public class AlgorithmBenchmark {
    public enum Strategy { FIFO, PRIORITY, PRIORITY_PLUS_GRIDLOCK }
    public record Result(Strategy strategy, long openingPerParticipantMinor, long settledCount, long settledValueMinor,
                         double averageSimulatedQueueWaitMillis, long unresolvedPayments, long gridlockBatches) { }

    public Result run(Workload workload, Strategy strategy, long opening) {
        ParticipantRegistry registry = new ParticipantRegistry();
        ParticipantLockManager locks = new ParticipantLockManager();
        PaymentService payments = new PaymentService(registry);
        LiquidityService liquidity = new LiquidityService(registry, locks);
        SimulationClock clock = new SimulationClock();
        SettlementCoordinator coordinator = new SettlementCoordinator(payments, liquidity, clock, locks);
        GridlockResolver resolver = new GridlockResolver(payments, liquidity, locks, clock, coordinator.settlementStore());
        for (var participant : workload.participants()) {
            registry.register(new Participant(participant.participantId(), participant.participantId()));
            liquidity.open(participant.participantId(), opening);
        }
        long batches = 0;
        for (var attempt : workload.payments()) {
            clock.at = Instant.parse("2026-01-01T00:00:00Z").plusNanos(attempt.offsetNanos());
            PaymentInstruction received = payments.submit(attempt.payment());
            if (received.status() == PaymentStatus.RECEIVED) payments.replace(received.queued(clock.instant()));
            drain(payments, coordinator, strategy);
            if (strategy == Strategy.PRIORITY_PLUS_GRIDLOCK) {
                for (GridlockCandidate candidate : new GridlockDetector().detect(payments.all()))
                    if (resolver.resolve(candidate).committed()) batches++;
                drain(payments, coordinator, strategy);
            }
        }
        List<PaymentInstruction> settled = payments.all().stream().filter(p -> p.status() == PaymentStatus.SETTLED).toList();
        long value = settled.stream().mapToLong(PaymentInstruction::amountMinor).sum();
        double wait = settled.stream().mapToLong(p -> Duration.between(p.queuedAt(), p.settledAt()).toNanos())
                .average().orElse(0) / 1_000_000;
        return new Result(strategy, opening, settled.size(), value, wait, payments.all().size() - settled.size(), batches);
    }

    private void drain(PaymentService payments, SettlementCoordinator coordinator, Strategy strategy) {
        Comparator<PaymentInstruction> order = strategy == Strategy.FIFO
                ? Comparator.comparing(PaymentInstruction::queuedAt).thenComparing(PaymentInstruction::paymentId)
                : QueuedPaymentComparator.INSTANCE;
        boolean progress;
        do {
            progress = false;
            List<PaymentInstruction> queued = payments.all().stream().filter(p -> p.status() == PaymentStatus.QUEUED).sorted(order).toList();
            for (PaymentInstruction payment : queued) {
                if (coordinator.processQueued(payment.paymentId()).status() == PaymentStatus.SETTLED) {
                    progress = true; break;
                }
                if (strategy == Strategy.FIFO) break;
            }
        } while (progress);
    }

    /** Exhaustive integer search: no monotonic scheduling assumption is needed. */
    public long minimumOpeningForAll(Workload workload, Strategy strategy) {
        long upper = workload.payments().stream().map(p -> p.payment()).distinct().mapToLong(PaymentInstruction::amountMinor).sum();
        for (long opening = 0; opening <= upper; opening++)
            if (run(workload, strategy, opening).unresolvedPayments() == 0) return opening;
        throw new IllegalStateException("no successful opening liquidity found");
    }

    private static class SimulationClock extends Clock {
        Instant at = Instant.parse("2026-01-01T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return at; }
    }
}
