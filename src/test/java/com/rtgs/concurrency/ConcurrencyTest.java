package com.rtgs.concurrency;

import static org.junit.jupiter.api.Assertions.*;

import com.rtgs.liquidity.LiquidityService;
import com.rtgs.payment.*;
import com.rtgs.settlement.SettlementCoordinator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class ConcurrencyTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test void ct1Oversubscription() throws Exception {
        for (int repeat = 0; repeat < 20; repeat++) {
            Fixture f = new Fixture(100, 0);
            List<Callable<PaymentInstruction>> work = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                String id = "p" + i;
                work.add(() -> f.coordinator.submitAndProcess(f.payment(id, "A", "B", 30)));
            }
            List<PaymentInstruction> results = run(work);
            assertEquals(3, results.stream().filter(p -> p.status() == PaymentStatus.SETTLED).count());
            assertEquals(10, f.liquidity.available("A"));
            assertEquals(90, f.liquidity.available("B"));
            assertEquals(3, f.coordinator.settlementCount());
        }
    }

    @Test void ct2DuplicateRace() throws Exception {
        Fixture f = new Fixture(100, 0);
        PaymentInstruction instruction = f.payment("same", "A", "B", 30);
        List<Callable<PaymentInstruction>> work = new ArrayList<>();
        for (int i = 0; i < 20; i++) work.add(() -> f.coordinator.submitAndProcess(instruction));
        run(work);
        assertEquals(70, f.liquidity.available("A"));
        assertEquals(30, f.liquidity.available("B"));
        assertEquals(1, f.coordinator.settlementCount());
    }

    @Test void ct3OpposingPaymentsNoDeadlock() throws Exception {
        Fixture f = new Fixture(100, 100);
        List<Callable<PaymentInstruction>> work = List.of(
                () -> f.coordinator.submitAndProcess(f.payment("ab", "A", "B", 50)),
                () -> f.coordinator.submitAndProcess(f.payment("ba", "B", "A", 50)));
        run(work);
        assertEquals(100, f.liquidity.available("A"));
        assertEquals(100, f.liquidity.available("B"));
        assertEquals(2, f.coordinator.settlementCount());
    }

    @Test void ct4RepeatedStress() throws Exception {
        for (int repeat = 0; repeat < 30; repeat++) {
            Fixture f = new Fixture(500, 500);
            List<Callable<PaymentInstruction>> work = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                String id = "p" + i;
                String source = i % 2 == 0 ? "A" : "B";
                String destination = i % 2 == 0 ? "B" : "A";
                work.add(() -> f.coordinator.submitAndProcess(f.payment(id, source, destination, 20)));
            }
            run(work);
            assertTrue(f.liquidity.available("A") >= 0);
            assertTrue(f.liquidity.available("B") >= 0);
            assertEquals(1000, f.liquidity.available("A") + f.liquidity.available("B"));
            assertEquals(f.payments.all().stream().filter(p -> p.status() == PaymentStatus.SETTLED).count(),
                    f.coordinator.settlementCount());
        }
    }

    private static <T> List<T> run(List<Callable<T>> work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(work.size(), 16));
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> job : work) futures.add(pool.submit(() -> { start.await(); return job.call(); }));
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) results.add(future.get(10, TimeUnit.SECONDS));
            return results;
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private static class Fixture {
        final ParticipantRegistry registry = new ParticipantRegistry();
        final PaymentService payments = new PaymentService(registry);
        final LiquidityService liquidity = new LiquidityService(registry);
        final SettlementCoordinator coordinator = new SettlementCoordinator(payments, liquidity,
                Clock.fixed(NOW, ZoneOffset.UTC));
        Fixture(long a, long b) {
            registry.register(new Participant("A", "Bank A"));
            registry.register(new Participant("B", "Bank B"));
            liquidity.open("A", a);
            liquidity.open("B", b);
        }
        PaymentInstruction payment(String id, String source, String destination, long amount) {
            return PaymentInstruction.received(id, source, destination, amount, PaymentPriority.NORMAL, NOW, null);
        }
    }
}
