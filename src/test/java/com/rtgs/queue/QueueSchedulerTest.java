package com.rtgs.queue;

import static org.junit.jupiter.api.Assertions.*;

import com.rtgs.concurrency.ParticipantLockManager;
import com.rtgs.liquidity.LiquidityService;
import com.rtgs.payment.*;
import com.rtgs.settlement.SettlementCoordinator;
import com.rtgs.settlement.SettlementType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QueueSchedulerTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    ParticipantRegistry registry;
    LiquidityService liquidity;
    PaymentService payments;
    SettlementCoordinator coordinator;
    SettlementQueue queue;
    QueueScheduler scheduler;

    @BeforeEach void setup() {
        registry = new ParticipantRegistry();
        for (String id : List.of("A", "B", "C")) registry.register(new Participant(id, "Bank " + id));
        ParticipantLockManager locks = new ParticipantLockManager();
        liquidity = new LiquidityService(registry, locks);
        for (String id : List.of("A", "B", "C")) liquidity.open(id, 0);
        payments = new PaymentService(registry);
        coordinator = new SettlementCoordinator(payments, liquidity, Clock.fixed(NOW, ZoneOffset.UTC), locks);
        queue = new SettlementQueue(payments);
        scheduler = new QueueScheduler(queue, coordinator, liquidity);
    }

    @Test void urgentBeatsHighAndHighBeatsNormal() {
        submit("normal", "A", "B", 10, PaymentPriority.NORMAL, null);
        submit("high", "A", "B", 10, PaymentPriority.HIGH, null);
        submit("urgent", "A", "B", 10, PaymentPriority.URGENT, null);
        assertEquals(List.of("urgent", "high", "normal"), queue.ordered().stream().map(PaymentInstruction::paymentId).toList());
        assertEquals(1, scheduler.injectAndRun("A", 10));
        assertEquals(PaymentStatus.SETTLED, payments.require("urgent").status());
        assertEquals(2, queue.size());
    }

    @Test void deadlinePreferenceThenSamePriorityFifo() {
        submit("late", "A", "B", 10, PaymentPriority.HIGH, NOW.plusSeconds(200));
        submit("early", "A", "B", 10, PaymentPriority.HIGH, NOW.plusSeconds(100));
        assertEquals("early", queue.ordered().getFirst().paymentId());
        PaymentInstruction earlierQueued = new PaymentInstruction("old", "A", "B", 10, PaymentPriority.NORMAL,
                NOW, null, PaymentStatus.QUEUED, NOW, null);
        PaymentInstruction laterQueued = new PaymentInstruction("new", "A", "B", 10, PaymentPriority.NORMAL,
                NOW, null, PaymentStatus.QUEUED, NOW.plusSeconds(1), null);
        assertTrue(QueuedPaymentComparator.INSTANCE.compare(earlierQueued, laterQueued) < 0);
    }

    @Test void deterministicPaymentIdTieBreak() {
        submit("z", "A", "B", 10, PaymentPriority.NORMAL, null);
        submit("a", "A", "B", 10, PaymentPriority.NORMAL, null);
        assertEquals(List.of("a", "z"), queue.ordered().stream().map(PaymentInstruction::paymentId).toList());
    }

    @Test void stopsWhenNoProgressAndRescansAfterCredit() {
        submit("ab", "A", "B", 10, PaymentPriority.HIGH, null);
        submit("bc", "B", "C", 10, PaymentPriority.NORMAL, null);
        assertEquals(0, scheduler.run());
        assertEquals(2, scheduler.injectAndRun("A", 10));
        assertEquals(0, queue.size());
        assertEquals(10, liquidity.available("C"));
        assertEquals(SettlementType.QUEUED, coordinator.settlement("ab").settlementType());
    }

    private void submit(String id, String source, String destination, long amount,
                        PaymentPriority priority, Instant deadline) {
        coordinator.submitAndProcess(PaymentInstruction.received(id, source, destination, amount, priority, NOW, deadline));
    }
}
