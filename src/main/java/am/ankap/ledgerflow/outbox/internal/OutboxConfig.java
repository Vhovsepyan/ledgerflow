package am.ankap.ledgerflow.outbox.internal;

import am.ankap.ledgerflow.outbox.EventPublisher;
import org.apache.kafka.clients.admin.NewTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration(proxyBeanMethods = false)
class OutboxConfig {

    private static final Logger log = LoggerFactory.getLogger(OutboxConfig.class);

    /** Used until a real publisher (Kafka) is on the classpath. */
    @Bean
    @ConditionalOnMissingBean(EventPublisher.class)
    EventPublisher loggingEventPublisher() {
        return record -> log.info("Publishing {} for {} {} (seq {})",
                record.eventType(), record.aggregateType(), record.aggregateId(), record.sequenceNo());
    }

    @Bean
    @ConditionalOnProperty(name = "ledgerflow.kafka.enabled", havingValue = "true", matchIfMissing = true)
    NewTopic paymentEventsTopic(
            @Value("${ledgerflow.kafka.payment-events-topic:payment-events}") String topic) {
        return TopicBuilder.name(topic)
                .partitions(3)
                .replicas(1)
                .build();
    }
}