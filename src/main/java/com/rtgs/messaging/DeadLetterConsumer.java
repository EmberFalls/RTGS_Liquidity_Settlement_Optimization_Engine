package com.rtgs.messaging;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@Profile("kafka")
public class DeadLetterConsumer {
    private final JdbcTemplate jdbc;
    public DeadLetterConsumer(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @KafkaListener(id = "rtgs-dlq-recorder", topics = "payment.dlq", groupId = "rtgs-payment-dlq", containerFactory = "dlqContainerFactory")
    public void record(String payload) {
        jdbc.update("INSERT INTO processing_failures(payment_id, failure_stage, failure_message) VALUES (NULL, 'KAFKA_DLQ', ?)",
                payload.length() > 2000 ? payload.substring(0, 2000) : payload);
    }
}
