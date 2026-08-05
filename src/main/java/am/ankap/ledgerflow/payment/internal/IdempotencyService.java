package am.ankap.ledgerflow.payment.internal;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
class IdempotencyService {

    private static final Duration ABANDONED_AFTER = Duration.ofMinutes(5);

    private final IdempotencyRecordRepository repository;

    IdempotencyService(IdempotencyRecordRepository repository) {
        this.repository = repository;
    }

    /**
     * Tries to take ownership of a key. Commits immediately in its own
     * transaction so a concurrent request sees the claim right away.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    ClaimResult claim(UUID merchantId, String idempotencyKey, String requestHash) {
        Optional<IdempotencyRecordEntity> existing =
                repository.findByMerchantIdAndIdempotencyKey(merchantId, idempotencyKey);

        if (existing.isPresent()) {
            return evaluate(existing.get(), requestHash);
        }

        try {
            IdempotencyRecordEntity claimed = repository.saveAndFlush(new IdempotencyRecordEntity(
                    UUID.randomUUID(), idempotencyKey, merchantId, requestHash));
            return ClaimResult.owned(claimed.getPaymentId());
        } catch (DataIntegrityViolationException lostTheRace) {
            return repository.findByMerchantIdAndIdempotencyKey(merchantId, idempotencyKey)
                    .map(record -> evaluate(record, requestHash))
                    .orElseThrow(() -> lostTheRace);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void complete(UUID merchantId, String idempotencyKey, int responseStatus,
                  String responseBody, UUID paymentId) {
        repository.findByMerchantIdAndIdempotencyKey(merchantId, idempotencyKey)
                .ifPresent(record -> record.complete(responseStatus, responseBody, paymentId));
    }

    private ClaimResult evaluate(IdempotencyRecordEntity record, String requestHash) {
        if (!record.matches(requestHash)) {
            return ClaimResult.conflict();
        }
        if (record.isCompleted()) {
            return ClaimResult.replay(record.getResponseStatus(), record.getResponseBody());
        }
        if (record.isAbandoned(Instant.now(), ABANDONED_AFTER)) {
            return ClaimResult.owned(record.getPaymentId());
        }
        return ClaimResult.inProgress();
    }
}