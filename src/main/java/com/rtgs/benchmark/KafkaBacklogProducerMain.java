package com.rtgs.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rtgs.payment.*;
import java.time.Instant;
import java.util.Properties;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.StringSerializer;

public class KafkaBacklogProducerMain {
    public static void main(String[] args) throws Exception {
        String prefix = args[0];
        int count = Integer.parseInt(args[1]);
        Properties properties = new Properties();
        properties.put("bootstrap.servers", "localhost:19092");
        properties.put("key.serializer", StringSerializer.class.getName());
        properties.put("value.serializer", StringSerializer.class.getName());
        properties.put("acks", "all");
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        int records = 0;
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(properties)) {
            for (int i = 0; i < count; i++) {
                var payment = PaymentInstruction.received(prefix + "p" + i, prefix + "A", prefix + "B", 1,
                        PaymentPriority.NORMAL, Instant.parse("2026-01-01T00:00:00Z"), null);
                var record = new ProducerRecord<String, String>("payment.incoming", prefix + "A", json.writeValueAsString(payment));
                producer.send(record).get(); records++;
                if (i % 5 == 0) { producer.send(record).get(); records++; }
            }
        }
        System.out.println("Published " + records + " records for " + count + " unique payments while the consumer is stopped.");
    }
}
