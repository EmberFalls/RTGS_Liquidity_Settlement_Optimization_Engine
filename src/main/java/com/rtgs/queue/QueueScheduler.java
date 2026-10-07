package com.rtgs.queue;

import com.rtgs.liquidity.LiquidityService;
import com.rtgs.payment.PaymentStatus;
import com.rtgs.settlement.SettlementCoordinator;

public class QueueScheduler {
    private final SettlementQueue queue;
    private final SettlementCoordinator coordinator;
    private final LiquidityService liquidity;

    public QueueScheduler(SettlementQueue queue, SettlementCoordinator coordinator, LiquidityService liquidity) {
        this.queue = queue;
        this.coordinator = coordinator;
        this.liquidity = liquidity;
    }

    /** Rescan after each settlement because a credit can make earlier queued work feasible. */
    public synchronized int run() {
        int settled = 0;
        boolean progress;
        do {
            progress = false;
            for (var payment : queue.ordered()) {
                if (coordinator.processQueued(payment.paymentId()).status() == PaymentStatus.SETTLED) {
                    settled++;
                    progress = true;
                    break;
                }
            }
        } while (progress);
        return settled;
    }

    public int injectAndRun(String participantId, long amountMinor) {
        liquidity.inject(participantId, amountMinor);
        return run();
    }
}
