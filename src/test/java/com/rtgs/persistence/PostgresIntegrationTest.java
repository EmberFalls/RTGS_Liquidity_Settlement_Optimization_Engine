package com.rtgs.persistence;

import static org.junit.jupiter.api.Assertions.*;

import com.rtgs.RtgsApplication;
import com.rtgs.common.RtgsEngine;
import com.rtgs.gridlock.GridlockResolutionResult;
import com.rtgs.payment.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = {
        "spring.profiles.active=postgres",
        "spring.datasource.url=jdbc:postgresql://127.0.0.1:55432/rtgs",
        "spring.datasource.username=rtgs",
        "spring.datasource.password="
})
@EnabledIfSystemProperty(named = "rtgs.postgres.test", matches = "true")
class PostgresIntegrationTest {
    @Autowired RtgsEngine engine;
    @Autowired JdbcTemplate jdbc;

    @Test void durableSettlementConstraintsAndRestart() {
        String prefix = unique();
        String a = prefix + "A", b = prefix + "B", paymentId = prefix + "P";
        engine.register(a, "Bank A", 100);
        engine.register(b, "Bank B", 0);
        assertEquals(PaymentStatus.SETTLED, engine.submit(payment(paymentId, a, b, 40)).status());
        assertEquals(PaymentStatus.SETTLED, engine.submit(payment(paymentId, a, b, 40)).status());
        assertEquals(60, engine.liquidity(a).availableMinor());
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "UPDATE liquidity_positions SET available_minor=-1 WHERE participant_id=?", a));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO settlements(settlement_id,payment_id,source_participant_id,destination_participant_id,
                  amount_minor,settlement_type,settled_at) VALUES (?, ?, ?, ?, 40, 'IMMEDIATE', now())
                """, UUID.randomUUID(), paymentId, a, b));

        SpringApplication app = new SpringApplication(RtgsApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setAdditionalProfiles("postgres");
        try (ConfigurableApplicationContext restarted = app.run(
                "--spring.datasource.url=jdbc:postgresql://127.0.0.1:55432/rtgs",
                "--spring.datasource.username=rtgs", "--spring.datasource.password=")) {
            RtgsEngine reloaded = restarted.getBean(RtgsEngine.class);
            assertEquals(60, reloaded.liquidity(a).availableMinor());
            assertEquals(PaymentStatus.SETTLED, reloaded.payment(paymentId).status());
            assertNotNull(reloaded.settlement(paymentId));
        }
    }

    @Test void transactionRollbackOnCreditOverflow() {
        String prefix = unique();
        String a = prefix + "A", b = prefix + "B", id = prefix + "P";
        engine.register(a, "Bank A", 10);
        engine.register(b, "Bank B", Long.MAX_VALUE);
        assertThrows(ArithmeticException.class, () -> engine.submit(payment(id, a, b, 1)));
        assertEquals(10, engine.liquidity(a).availableMinor());
        assertEquals(Long.MAX_VALUE, engine.liquidity(b).availableMinor());
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM payments WHERE payment_id=?", Long.class, id));
    }

    @Test void incomingCreditReevaluatesBlockedQueue() {
        String prefix=unique();
        engine.register(prefix+"A","A",0);
        engine.register(prefix+"B","B",0);
        engine.register(prefix+"C","C",100);
        assertEquals(PaymentStatus.QUEUED,engine.submit(payment(prefix+"ab",prefix+"A",prefix+"B",50)).status());
        engine.submit(payment(prefix+"ca",prefix+"C",prefix+"A",50));
        assertEquals(PaymentStatus.SETTLED,engine.payment(prefix+"ab").status());
        assertEquals(50,engine.liquidity(prefix+"B").availableMinor());
    }

    @Test void gridlockBatchCommitsAtomically() {
        String prefix = unique();
        String a = prefix + "A", b = prefix + "B", c = prefix + "C";
        engine.register(a, "Bank A", 20);
        engine.register(b, "Bank B", 0);
        engine.register(c, "Bank C", 0);
        engine.submit(payment(prefix + "ab", a, b, 70));
        engine.submit(payment(prefix + "bc", b, c, 60));
        engine.submit(payment(prefix + "ca", c, a, 50));
        GridlockResolutionResult result = engine.runGridlock().stream()
                .filter(GridlockResolutionResult::committed).findFirst().orElseThrow();
        assertEquals(3, result.settledPaymentIds().size());
        assertEquals(0, engine.liquidity(a).availableMinor());
        assertEquals(10, engine.liquidity(b).availableMinor());
        assertEquals(10, engine.liquidity(c).availableMinor());
        for (String id : result.settledPaymentIds()) {
            assertEquals(PaymentStatus.SETTLED, engine.payment(id).status());
            assertEquals(result.batchId(), engine.settlement(id).batchId());
        }
    }

    @Test void gridlockBatchRollsBackOnDatabaseFailure() {
        String prefix = unique();
        String a = prefix + "A", b = prefix + "B", c = prefix + "C";
        String ab = prefix + "ab", bc = prefix + "bc", ca = prefix + "ca";
        engine.register(a, "Bank A", 20);
        engine.register(b, "Bank B", 0);
        engine.register(c, "Bank C", 0);
        engine.submit(payment(ab, a, b, 70));
        engine.submit(payment(bc, b, c, 60));
        engine.submit(payment(ca, c, a, 50));
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION rtgs_test_reject_settlement() RETURNS trigger AS $$
                BEGIN
                  IF NEW.payment_id = '%s' THEN RAISE EXCEPTION 'injected batch failure'; END IF;
                  RETURN NEW;
                END;
                $$ LANGUAGE plpgsql
                """.formatted(bc));
        jdbc.execute("CREATE TRIGGER rtgs_test_reject BEFORE INSERT ON settlements FOR EACH ROW EXECUTE FUNCTION rtgs_test_reject_settlement()");
        try {
            assertThrows(Exception.class, engine::runGridlock);
            for (String id : java.util.List.of(ab, bc, ca)) {
                assertEquals(PaymentStatus.QUEUED, engine.payment(id).status());
                assertNull(engine.settlement(id));
            }
            assertEquals(20, engine.liquidity(a).availableMinor());
            assertEquals(0, engine.liquidity(b).availableMinor());
            assertEquals(0, engine.liquidity(c).availableMinor());
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS rtgs_test_reject ON settlements");
            jdbc.execute("DROP FUNCTION IF EXISTS rtgs_test_reject_settlement()");
        }
    }

    private static PaymentInstruction payment(String id, String source, String destination, long amount) {
        return PaymentInstruction.received(id, source, destination, amount, PaymentPriority.NORMAL,
                Instant.parse("2026-01-01T00:00:00Z"), null);
    }
    private static String unique() { return UUID.randomUUID().toString().substring(0, 8); }
}
