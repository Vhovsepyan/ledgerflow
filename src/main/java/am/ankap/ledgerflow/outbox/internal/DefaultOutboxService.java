package am.ankap.ledgerflow.outbox.internal;

import am.ankap.ledgerflow.outbox.OutboxService;
import org.springframework.stereotype.Service;
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
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void append(String aggregateType, UUID aggregateId, String eventType, Object payload) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Outbox events must be appended inside a transaction");
        }
        repository.save(new OutboxEventEntity(
                aggregateType, aggregateId, eventType, objectMapper.writeValueAsString(payload)));
    }
}