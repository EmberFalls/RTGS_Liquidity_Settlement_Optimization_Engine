package com.rtgs.messaging;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
@Profile("kafka")
@EnableKafka
public class KafkaMessagingConfiguration {
    @Bean NewTopic incomingTopic() { return TopicBuilder.name("payment.incoming").partitions(3).replicas(1).build(); }
    @Bean NewTopic dlqTopic() { return TopicBuilder.name("payment.dlq").partitions(3).replicas(1).build(); }

    @Bean DefaultErrorHandler paymentErrorHandler(KafkaTemplate<String, String> kafka) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafka,
                (record, error) -> new org.apache.kafka.common.TopicPartition("payment.dlq", record.partition()));
        return new DefaultErrorHandler(recoverer, new FixedBackOff(100, 2));
    }

    @Bean ConcurrentKafkaListenerContainerFactory<Object, Object> dlqContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer, ConsumerFactory<Object, Object> consumers) {
        var factory = new ConcurrentKafkaListenerContainerFactory<Object, Object>();
        configurer.configure(factory, consumers);
        // A failed DLQ database write stays in Kafka for replay after recovery.
        // Stop this recorder rather than republishing failures to its own input topic.
        factory.setCommonErrorHandler(new CommonContainerStoppingErrorHandler());
        return factory;
    }
}
