package am.ankap.ledgerflow.payment.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecordEntity, UUID> {

    Optional<IdempotencyRecordEntity> findByMerchantIdAndIdempotencyKey(UUID merchantId, String idempotencyKey);
}