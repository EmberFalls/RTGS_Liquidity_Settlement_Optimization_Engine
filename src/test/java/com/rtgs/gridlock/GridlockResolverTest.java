package com.rtgs.gridlock;

import static org.junit.jupiter.api.Assertions.*;

import com.rtgs.concurrency.ParticipantLockManager;
import com.rtgs.liquidity.LiquidityService;
import com.rtgs.payment.*;
import com.rtgs.settlement.SettlementCoordinator;
import com.rtgs.settlement.SettlementType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class GridlockResolverTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test void feasibleThreeNodeCycleCommitsTogether() {
        Fixture f = new Fixture(20, 0, 0, 0);
        f.submit("ab", "A", "B", 70, PaymentPriority.NORMAL);
        f.submit("bc", "B", "C", 60, PaymentPriority.NORMAL);
        f.submit("ca", "C", "A", 50, PaymentPriority.NORMAL);
        GridlockCandidate candidate = f.candidate();
        var result = f.resolver.resolve(candidate);
        assertTrue(result.committed());
        assertEquals(3, result.settledPaymentIds().size());
        assertEquals(0, f.liquidity.available("A"));
        assertEquals(10, f.liquidity.available("B"));
        assertEquals(10, f.liquidity.available("C"));
        assertEquals(3, f.coordinator.settlementCount());
        for (String id : result.settledPaymentIds()) {
            assertEquals(result.batchId(), f.coordinator.settlement(id).batchId());
            assertEquals(SettlementType.GRIDLOCK_BATCH, f.coordinator.settlement(id).settlementType());
        }
        assertFalse(f.resolver.resolve(candidate).committed());
    }

    @Test void infeasibleCycleAppliesNone() {
        Fixture f = new Fixture(0, 0, 0, 0);
        f.submit("ab", "A", "B", 70, PaymentPriority.NORMAL);
        f.submit("ba", "B", "A", 50, PaymentPriority.NORMAL);
        var result = f.resolve();
        assertFalse(result.committed());
        assertEquals(0, f.coordinator.settlementCount());
        assertEquals(PaymentStatus.QUEUED, f.payments.require("ab").status());
        assertEquals(0, f.liquidity.available("A"));
    }

    @Test void mixedPriorityPrunesNormalFirst() {
        Fixture f = new Fixture(0, 0, 0, 0);
        f.submit("urgent", "A", "B", 70, PaymentPriority.URGENT);
        f.submit("high", "B", "A", 70, PaymentPriority.HIGH);
        f.submit("normal", "A", "B", 20, PaymentPriority.NORMAL);
        var result = f.resolve();
        assertTrue(result.committed());
        assertEquals(List.of("normal"), result.prunedPaymentIds());
        assertEquals(List.of("high", "urgent"), result.settledPaymentIds());
        assertEquals(PaymentStatus.QUEUED, f.payments.require("normal").status());
    }

    @Test void staleCandidateRejectedWithoutEffect() {
        Fixture f = new Fixture(0, 0, 0, 0);
        f.submit("ab", "A", "B", 70, PaymentPriority.NORMAL);
        f.submit("ba", "B", "A", 70, PaymentPriority.NORMAL);
        GridlockCandidate candidate = f.candidate();
        f.liquidity.inject("A", 70);
        f.coordinator.processQueued("ab");
        assertFalse(f.resolver.resolve(candidate).committed());
        assertEquals(1, f.coordinator.settlementCount());
        assertEquals(0, f.liquidity.available("A"));
    }

    @Test void concurrentNormalSettlementVersusBatch() throws Exception {
        for (int repeat = 0; repeat < 30; repeat++) {
            Fixture f = new Fixture(20, 0, 0, 0);
            f.submit("ab", "A", "B", 70, PaymentPriority.NORMAL);
            f.submit("bc", "B", "C", 60, PaymentPriority.NORMAL);
            f.submit("ca", "C", "A", 50, PaymentPriority.NORMAL);
            GridlockCandidate candidate = f.candidate();
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            try {
                Future<GridlockResolutionResult> batch = pool.submit(() -> { start.await(); return f.resolver.resolve(candidate); });
                Future<PaymentInstruction> normal = pool.submit(() -> { start.await(); return f.submit("ad", "A", "D", 20, PaymentPriority.NORMAL); });
                start.countDown();
                GridlockResolutionResult result = batch.get(10, TimeUnit.SECONDS);
                normal.get(10, TimeUnit.SECONDS);
                assertEquals(20, f.totalLiquidity());
                assertTrue(f.liquidity.available("A") >= 0);
                assertEquals(result.committed() ? 3 : 1, f.coordinator.settlementCount());
            } finally {
                pool.shutdownNow();
                assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
            }
        }
    }

    private static class Fixture {
        final ParticipantRegistry registry = new ParticipantRegistry();
        final ParticipantLockManager locks = new ParticipantLockManager();
        final LiquidityService liquidity = new LiquidityService(registry, locks);
        final PaymentService payments = new PaymentService(registry);
        final SettlementCoordinator coordinator = new SettlementCoordinator(payments, liquidity, Clock.fixed(NOW, ZoneOffset.UTC), locks);
        final GridlockResolver resolver = new GridlockResolver(payments, liquidity, locks,
                Clock.fixed(NOW, ZoneOffset.UTC), coordinator.settlementStore());
        Fixture(long a, long b, long c, long d) {
            for (String id : List.of("A", "B", "C", "D")) registry.register(new Participant(id, "Bank " + id));
            liquidity.open("A", a); liquidity.open("B", b); liquidity.open("C", c); liquidity.open("D", d);
        }
        PaymentInstruction submit(String id, String source, String destination, long amount, PaymentPriority priority) {
            return coordinator.submitAndProcess(PaymentInstruction.received(id, source, destination, amount, priority, NOW, null));
        }
        GridlockCandidate candidate() { return new GridlockDetector().detect(payments.all()).getFirst(); }
        GridlockResolutionResult resolve() { return resolver.resolve(candidate()); }
        long totalLiquidity() { return List.of("A", "B", "C", "D").stream().mapToLong(liquidity::available).sum(); }
    }
}
