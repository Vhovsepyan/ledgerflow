package am.ankap.ledgerflow.payment.internal;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
class IdempotencyService {

    private static final Duration ABANDONED_AFTER = Duration.ofMinutes(5);

    private final IdempotencyRecordRepository repository;
    private final JdbcClient jdbcClient;

    IdempotencyService(IdempotencyRecordRepository repository, JdbcClient jdbcClient) {
        this.repository = repository;
        this.jdbcClient = jdbcClient;
    }

    /**
     * Tries to take ownership of a key. Commits immediately in its own transaction
     * so a concurrent request sees the claim right away.
     * Uses ON CONFLICT DO NOTHING: a lost race returns 0 rows instead of throwing,
     * which keeps the transaction usable for the follow-up read.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    ClaimResult claim(UUID merchantId, String idempotencyKey, String requestHash) {
        int inserted = jdbcClient.sql("""
                        insert into idempotency_record
                            (id, idempotency_key, merchant_id, request_hash, status)
                        values (:id, :key, :merchantId, :hash, 'IN_PROGRESS')
                        on conflict (merchant_id, idempotency_key) do nothing
                        """)
                .param("id", UUID.randomUUID())
                .param("key", idempotencyKey)
                .param("merchantId", merchantId)
                .param("hash", requestHash)
                .update();

        if (inserted == 1) {
            return ClaimResult.owned(null);
        }

        return repository.findByMerchantIdAndIdempotencyKey(merchantId, idempotencyKey)
                .map(record -> evaluate(record, requestHash))
                .orElseGet(ClaimResult::inProgress);
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