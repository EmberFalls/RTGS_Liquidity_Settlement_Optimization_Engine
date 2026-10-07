package com.rtgs.messaging;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rtgs.common.RtgsEngine;
import com.rtgs.payment.*;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.profiles.active=postgres,kafka",
        "spring.datasource.url=jdbc:postgresql://127.0.0.1:55432/rtgs",
        "spring.datasource.username=rtgs",
        "spring.datasource.password="
})
@AutoConfigureMockMvc
@EmbeddedKafka(partitions = 3, topics = {"payment.incoming", "payment.dlq"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@EnabledIfSystemProperty(named = "rtgs.kafka.test", matches = "true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class KafkaIntegrationTest {
    @Autowired RtgsEngine engine;
    @Autowired MockMvc mvc;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired KafkaListenerEndpointRegistry listeners;

    @Test @Order(1) void restPublishesAndConsumerSettlesDuplicateOnce() throws Exception {
        String prefix = UUID.randomUUID().toString().substring(0, 8);
        String a = prefix + "A", b = prefix + "B", id = prefix + "P";
        engine.register(a, "Bank A", 100);
        engine.register(b, "Bank B", 0);
        mvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON).content("""
                {"paymentId":"%s","sourceParticipantId":"%s","destinationParticipantId":"%s",
                 "amountMinor":40,"priority":"NORMAL"}
                """.formatted(id, a, b)))
                .andExpect(status().isOk());
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertEquals(PaymentStatus.SETTLED, engine.payment(id).status()));
        PaymentInstruction duplicate = PaymentInstruction.received(id, a, b, 40, PaymentPriority.NORMAL, Instant.now(), null);
        kafka.send("payment.incoming", a, json.writeValueAsString(duplicate)).get();
        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertEquals(60, engine.liquidity(a).availableMinor());
            assertEquals(40, engine.liquidity(b).availableMinor());
            assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM settlements WHERE payment_id=?", Long.class, id));
        });
    }

    @Test @Order(2) void malformedEventRetriesThenMovesToDlq() throws Exception {
        long before = jdbc.queryForObject("SELECT count(*) FROM processing_failures WHERE failure_stage='KAFKA_DLQ'", Long.class);
        kafka.send("payment.incoming", "broken", "not-json-" + UUID.randomUUID()).get();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertTrue(
                jdbc.queryForObject("SELECT count(*) FROM processing_failures WHERE failure_stage='KAFKA_DLQ'", Long.class) > before));
    }

    @Test @Order(3) void dlqDatabaseFailureStopsRecorderAndReplaysAfterRecovery() throws Exception {
        String marker="dlq-stop-"+UUID.randomUUID();
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION rtgs_test_dlq_failure() RETURNS trigger AS $$
                BEGIN
                  IF NEW.failure_message LIKE '%%%s%%' THEN RAISE EXCEPTION 'injected DLQ persistence failure'; END IF;
                  RETURN NEW;
                END; $$ LANGUAGE plpgsql
                """.formatted(marker));
        jdbc.execute("CREATE TRIGGER rtgs_test_dlq_failure BEFORE INSERT ON processing_failures FOR EACH ROW EXECUTE FUNCTION rtgs_test_dlq_failure()");
        var container=listeners.getListenerContainer("rtgs-dlq-recorder");
        try {
            kafka.send("payment.incoming","broken",marker).get();
            await().atMost(Duration.ofSeconds(20)).until(() -> !container.isRunning());
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS rtgs_test_dlq_failure ON processing_failures");
            jdbc.execute("DROP FUNCTION IF EXISTS rtgs_test_dlq_failure()");
        }
        container.start();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertTrue(jdbc.queryForObject(
                "SELECT count(*) FROM processing_failures WHERE failure_message LIKE ?",Long.class,"%"+marker+"%")>0));
    }
}
