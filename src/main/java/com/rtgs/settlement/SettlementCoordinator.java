package com.rtgs.settlement;

import com.rtgs.concurrency.ParticipantLockManager;
import com.rtgs.liquidity.LiquidityService;
import com.rtgs.payment.PaymentInstruction;
import com.rtgs.payment.PaymentService;
import com.rtgs.payment.PaymentStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Settlement flow guarded by consistently ordered participant locks. */
public class SettlementCoordinator {
    private final PaymentService payments;
    private final LiquidityService liquidity;
    private final Clock clock;
    private final ParticipantLockManager locks;
    private final Map<String, SettlementRecord> settlements = new ConcurrentHashMap<>();

    public SettlementCoordinator(PaymentService payments, LiquidityService liquidity, Clock clock) {
        this(payments, liquidity, clock, new ParticipantLockManager());
    }

    public SettlementCoordinator(PaymentService payments, LiquidityService liquidity, Clock clock,
                                 ParticipantLockManager locks) {
        this.payments = payments;
        this.liquidity = liquidity;
        this.clock = clock;
        this.locks = locks;
    }

    public PaymentInstruction submitAndProcess(PaymentInstruction instruction) {
        PaymentInstruction payment = payments.submit(instruction);
        return process(payment.paymentId());
    }

    public PaymentInstruction process(String paymentId) {
        PaymentInstruction payment = payments.require(paymentId);
        return locks.withLocks(List.of(payment.sourceParticipantId(), payment.destinationParticipantId()),
                () -> processLocked(paymentId, false));
    }

    public PaymentInstruction processQueued(String paymentId) {
        PaymentInstruction payment = payments.require(paymentId);
        return locks.withLocks(List.of(payment.sourceParticipantId(), payment.destinationParticipantId()),
                () -> processLocked(paymentId, true));
    }

    private PaymentInstruction processLocked(String paymentId, boolean allowQueued) {
        PaymentInstruction payment = payments.require(paymentId);
        if (payment.status() != PaymentStatus.RECEIVED && !(allowQueued && payment.status() == PaymentStatus.QUEUED)) return payment;
        String source = payment.sourceParticipantId();
        String destination = payment.destinationParticipantId();
        long available = liquidity.available(source);
        liquidity.available(destination);
        Instant now = clock.instant();
        if (available < payment.amountMinor()) {
            if (payment.status() == PaymentStatus.QUEUED) return payment;
            PaymentInstruction queued = payment.queued(now);
            payments.replace(queued);
            return queued;
        }
        liquidity.transfer(source, destination, payment.amountMinor());
        PaymentInstruction settled = payment.settled(now);
        settlements.put(paymentId, new SettlementRecord(UUID.randomUUID(), paymentId, source, destination,
                payment.amountMinor(), payment.status() == PaymentStatus.QUEUED ? SettlementType.QUEUED : SettlementType.IMMEDIATE, now, null));
        payments.replace(settled);
        return settled;
    }

    public SettlementRecord settlement(String paymentId) { return settlements.get(paymentId); }
    public int settlementCount() { return settlements.size(); }
    public Map<String, SettlementRecord> settlementStore() { return settlements; }
}
