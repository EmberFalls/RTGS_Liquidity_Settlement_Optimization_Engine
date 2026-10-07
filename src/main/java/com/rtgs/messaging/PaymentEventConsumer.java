package com.rtgs.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rtgs.payment.PaymentInstruction;
import com.rtgs.persistence.PostgresRtgsEngine;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@Profile("kafka")
public class PaymentEventConsumer {
    private final PostgresRtgsEngine durable;
    private final ObjectMapper json;
    public PaymentEventConsumer(PostgresRtgsEngine durable, ObjectMapper json) {
        this.durable = durable; this.json = json;
    }

    @KafkaListener(topics = "payment.incoming")
    public void receive(String payload) throws Exception {
        durable.submit(json.readValue(payload, PaymentInstruction.class));
    }
}
