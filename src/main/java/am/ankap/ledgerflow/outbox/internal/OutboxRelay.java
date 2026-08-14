package am.ankap.ledgerflow.outbox.internal;

import am.ankap.ledgerflow.outbox.EventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int BATCH_SIZE = 50;

    private final OutboxEventRepository repository;
    private final EventPublisher eventPublisher;

    OutboxRelay(OutboxEventRepository repository, EventPublisher eventPublisher) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
    }

    @Scheduled(fixedDelayString = "${ledgerflow.outbox.poll-interval:1s}")
    @Transactional
    void publishPendingEvents() {
        List<OutboxEventEntity> batch = repository.lockUnpublishedBatch(BATCH_SIZE);
        if (batch.isEmpty()) {
            return;
        }

        for (OutboxEventEntity event : batch) {
            var record = event.toRecord();

            // Log this publish under the trace of the request that created the
            // event, not under the scheduled job's own context.
            if (record.hasTrace()) {
                MDC.put("traceId", record.traceId());
                MDC.put("spanId", record.spanId());
            }
            try {
                eventPublisher.publish(record);
                event.markPublished();
            } catch (RuntimeException e) {
                // Stop the batch: events for one aggregate must keep their order,
                // and continuing past a failure could publish a later event first.
                event.markFailed(e.getMessage());
                log.error("Outbox publish failed at event {}, stopping batch", record.id(), e);
                break;
            } finally {
                MDC.remove("traceId");
                MDC.remove("spanId");
            }
        }
    }
}