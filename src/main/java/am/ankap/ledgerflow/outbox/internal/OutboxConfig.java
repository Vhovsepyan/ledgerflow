package am.ankap.ledgerflow.outbox.internal;

import am.ankap.ledgerflow.outbox.EventPublisher;
import am.ankap.ledgerflow.outbox.OutboxRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
}