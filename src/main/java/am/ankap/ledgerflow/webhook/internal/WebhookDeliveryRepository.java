package am.ankap.ledgerflow.webhook.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

interface WebhookDeliveryRepository extends JpaRepository<WebhookDeliveryEntity, UUID> {

    @Query(value = """
                   select * from webhook_delivery
                    where status = 'PENDING'
                      and next_retry_at <= :now
                    order by next_retry_at
                    limit :batchSize
                    for update skip locked
                   """, nativeQuery = true)
    List<WebhookDeliveryEntity> lockDueDeliveries(@Param("now") Instant now,
                                                  @Param("batchSize") int batchSize);
}