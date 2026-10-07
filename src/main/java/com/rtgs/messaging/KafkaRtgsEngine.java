package com.rtgs.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rtgs.common.RtgsEngine;
import com.rtgs.gridlock.GridlockResolutionResult;
import com.rtgs.liquidity.LiquidityPosition;
import com.rtgs.payment.Participant;
import com.rtgs.payment.PaymentInstruction;
import com.rtgs.persistence.PostgresRtgsEngine;
import com.rtgs.settlement.SettlementRecord;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

/** Ingestion decorator. PostgreSQL still owns every financial state change. */
@Service
@Primary
@Profile("kafka")
public class KafkaRtgsEngine implements RtgsEngine {
    private final PostgresRtgsEngine durable;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper json;

    public KafkaRtgsEngine(PostgresRtgsEngine durable, KafkaTemplate<String, String> kafka, ObjectMapper json) {
        this.durable = durable; this.kafka = kafka; this.json = json;
    }

    @Override public PaymentInstruction submit(PaymentInstruction payment) {
        try {
            kafka.send("payment.incoming", payment.sourceParticipantId(), json.writeValueAsString(payment))
                    .get(10, TimeUnit.SECONDS);
            return payment;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("cannot serialize payment", e);
        } catch (Exception e) {
            throw new IllegalStateException("Kafka did not accept payment", e);
        }
    }

    @Override public Participant register(String id, String name, long opening) { return durable.register(id, name, opening); }
    @Override public PaymentInstruction payment(String id) { return durable.payment(id); }
    @Override public List<PaymentInstruction> queued() { return durable.queued(); }
    @Override public LiquidityPosition liquidity(String id) { return durable.liquidity(id); }
    @Override public int injectAndRun(String id, long amount) { return durable.injectAndRun(id, amount); }
    @Override public int runScheduler() { return durable.runScheduler(); }
    @Override public List<GridlockResolutionResult> runGridlock() { return durable.runGridlock(); }
    @Override public SettlementRecord settlement(String id) { return durable.settlement(id); }
}
