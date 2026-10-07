package com.rtgs.common;

import com.rtgs.gridlock.*;
import com.rtgs.liquidity.*;
import com.rtgs.payment.*;
import com.rtgs.queue.*;
import com.rtgs.settlement.*;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!postgres")
public class InMemoryRtgsEngine implements RtgsEngine {
    private final ParticipantRegistry participants;
    private final LiquidityService liquidity;
    private final PaymentService payments;
    private final SettlementCoordinator coordinator;
    private final SettlementQueue queue;
    private final QueueScheduler scheduler;
    private final GridlockResolver resolver;

    public InMemoryRtgsEngine(ParticipantRegistry participants, LiquidityService liquidity, PaymentService payments,
                              SettlementCoordinator coordinator, SettlementQueue queue, QueueScheduler scheduler,
                              GridlockResolver resolver) {
        this.participants = participants; this.liquidity = liquidity; this.payments = payments;
        this.coordinator = coordinator; this.queue = queue; this.scheduler = scheduler; this.resolver = resolver;
    }
    @Override public Participant register(String id, String name, long opening) {
        Participant participant = new Participant(id, name);
        new LiquidityPosition(id, opening);
        participants.register(participant);
        liquidity.open(id, opening);
        return participant;
    }
    @Override public PaymentInstruction submit(PaymentInstruction payment) {
        PaymentInstruction result = coordinator.submitAndProcess(payment);
        if (result.status() == PaymentStatus.SETTLED) scheduler.run();
        return result;
    }
    @Override public PaymentInstruction payment(String id) { return payments.require(id); }
    @Override public List<PaymentInstruction> queued() { return queue.ordered(); }
    @Override public LiquidityPosition liquidity(String id) { return liquidity.require(id); }
    @Override public int injectAndRun(String id, long amount) { return scheduler.injectAndRun(id, amount); }
    @Override public int runScheduler() { return scheduler.run(); }
    @Override public List<GridlockResolutionResult> runGridlock() {
        List<GridlockResolutionResult> results = new GridlockDetector().detect(payments.all()).stream().map(resolver::resolve).toList();
        if (results.stream().anyMatch(GridlockResolutionResult::committed)) scheduler.run();
        return results;
    }
    @Override public SettlementRecord settlement(String id) { return coordinator.settlement(id); }
}
