package com.rtgs.payment;

import static org.junit.jupiter.api.Assertions.*;

import com.rtgs.liquidity.LiquidityPosition;
import com.rtgs.settlement.SettlementRecord;
import com.rtgs.settlement.SettlementType;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DomainValidationTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test void validModelAndTransitions() {
        ParticipantRegistry registry = new ParticipantRegistry();
        registry.register(new Participant("A", "Bank A"));
        registry.register(new Participant("B", "Bank B"));
        PaymentInstruction payment = PaymentInstruction.received("p1", "A", "B", 100, PaymentPriority.HIGH, NOW, null);
        registry.validate(payment);
        assertEquals(PaymentStatus.SETTLED, payment.queued(NOW).settled(NOW).status());
        assertEquals(0, new LiquidityPosition("A", 0).availableMinor());
        assertEquals("p1", new SettlementRecord(UUID.randomUUID(), "p1", "A", "B", 100,
                SettlementType.IMMEDIATE, NOW, null).paymentId());
    }

    @Test void rejectsInvalidPayments() {
        assertThrows(IllegalArgumentException.class, () -> payment("p", "A", "B", 0, PaymentPriority.NORMAL));
        assertThrows(IllegalArgumentException.class, () -> payment("p", "A", "B", -1, PaymentPriority.NORMAL));
        assertThrows(IllegalArgumentException.class, () -> payment("p", "A", "A", 1, PaymentPriority.NORMAL));
        assertThrows(NullPointerException.class, () -> payment("p", "A", "B", 1, null));
        assertThrows(IllegalArgumentException.class, () -> PaymentPriority.valueOf("INVALID"));
        assertThrows(IllegalArgumentException.class, () -> PaymentInstruction.received("p", "A", "B", 1,
                PaymentPriority.NORMAL, NOW, NOW.minusSeconds(1)));
    }

    @Test void rejectsUnknownParticipant() {
        ParticipantRegistry registry = new ParticipantRegistry();
        registry.register(new Participant("A", "Bank A"));
        assertThrows(IllegalArgumentException.class, () -> registry.validate(payment("p", "A", "B", 1, PaymentPriority.NORMAL)));
    }

    @Test void rejectsInvalidFinancialState() {
        assertThrows(IllegalArgumentException.class, () -> new LiquidityPosition("A", -1));
        assertThrows(IllegalArgumentException.class, () -> new SettlementRecord(UUID.randomUUID(), "p", "A", "B",
                1, SettlementType.GRIDLOCK_BATCH, NOW, null));
        PaymentInstruction payment = payment("p", "A", "B", 1, PaymentPriority.NORMAL).settled(NOW);
        assertThrows(IllegalStateException.class, () -> payment.settled(NOW));
    }

    private static PaymentInstruction payment(String id, String source, String destination, long amount, PaymentPriority priority) {
        return PaymentInstruction.received(id, source, destination, amount, priority, NOW, null);
    }
}
