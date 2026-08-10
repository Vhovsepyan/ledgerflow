package am.ankap.ledgerflow.outbox.internal;

import am.ankap.ledgerflow.outbox.EventPublisher;
import am.ankap.ledgerflow.outbox.OutboxRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnMissingBean(EventPublisher.class)
class LoggingEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingEventPublisher.class);

    @Override
    public void publish(OutboxRecord record) {
        log.info("Publishing {} for {} {} (seq {})",
                record.eventType(), record.aggregateType(), record.aggregateId(), record.sequenceNo());
    }
}