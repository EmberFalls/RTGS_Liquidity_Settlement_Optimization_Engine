package com.rtgs.settlement;

import static org.junit.jupiter.api.Assertions.*;

import com.rtgs.liquidity.LiquidityService;
import com.rtgs.payment.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SettlementCoordinatorTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    ParticipantRegistry participants;
    PaymentService payments;
    LiquidityService liquidity;
    SettlementCoordinator coordinator;

    @BeforeEach void setup() {
        participants = new ParticipantRegistry();
        participants.register(new Participant("A", "Bank A"));
        participants.register(new Participant("B", "Bank B"));
        payments = new PaymentService(participants);
        liquidity = new LiquidityService(participants);
        liquidity.open("A", 100);
        liquidity.open("B", 20);
        coordinator = new SettlementCoordinator(payments, liquidity, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test void settlesAndMovesLiquidity() {
        assertEquals(PaymentStatus.SETTLED, coordinator.submitAndProcess(payment("p", 40)).status());
        assertEquals(60, liquidity.available("A"));
        assertEquals(60, liquidity.available("B"));
        assertEquals(SettlementType.IMMEDIATE, coordinator.settlement("p").settlementType());
    }

    @Test void insufficientLiquidityQueuesWithoutFinancialEffect() {
        assertEquals(PaymentStatus.QUEUED, coordinator.submitAndProcess(payment("p", 101)).status());
        assertEquals(100, liquidity.available("A"));
        assertEquals(20, liquidity.available("B"));
        assertNull(coordinator.settlement("p"));
    }

    @Test void exactLiquiditySettles() {
        assertEquals(PaymentStatus.SETTLED, coordinator.submitAndProcess(payment("p", 100)).status());
        assertEquals(0, liquidity.available("A"));
    }

    @Test void invalidPaymentHasNoFinancialEffect() {
        assertThrows(IllegalArgumentException.class, () -> coordinator.submitAndProcess(
                PaymentInstruction.received("p", "A", "UNKNOWN", 40, PaymentPriority.NORMAL, NOW, null)));
        assertEquals(100, liquidity.available("A"));
        assertEquals(0, coordinator.settlementCount());
    }

    @Test void duplicateDirectProcessingHasNoFinancialEffect() {
        PaymentInstruction instruction = payment("p", 40);
        coordinator.submitAndProcess(instruction);
        coordinator.submitAndProcess(instruction);
        coordinator.process("p");
        assertEquals(60, liquidity.available("A"));
        assertEquals(60, liquidity.available("B"));
        assertEquals(1, coordinator.settlementCount());
        assertThrows(IllegalArgumentException.class, () -> coordinator.submitAndProcess(payment("p", 41)));
    }

    private PaymentInstruction payment(String id, long amount) {
        return PaymentInstruction.received(id, "A", "B", amount, PaymentPriority.NORMAL, NOW, null);
    }
}
