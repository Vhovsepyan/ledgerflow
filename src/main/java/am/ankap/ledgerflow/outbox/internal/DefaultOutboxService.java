package am.ankap.ledgerflow.outbox.internal;

import am.ankap.ledgerflow.outbox.OutboxService;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

@Service
class DefaultOutboxService implements OutboxService {

    private final OutboxEventRepository repository;
    private final ObjectMapper objectMapper;

    DefaultOutboxService(OutboxEventRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(String aggregateType, UUID aggregateId, String eventType, Object payload) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Outbox events must be appended inside a transaction");
        }

        // The relay publishes this later, on another thread. Trace context is
        // thread-local, so it has to be written down here — it cannot be inherited.
        repository.save(new OutboxEventEntity(
                aggregateType,
                aggregateId,
                eventType,
                objectMapper.writeValueAsString(payload),
                MDC.get("traceId"),
                MDC.get("spanId")));
    }
}