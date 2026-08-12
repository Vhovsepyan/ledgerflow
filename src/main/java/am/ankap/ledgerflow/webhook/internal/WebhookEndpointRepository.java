package am.ankap.ledgerflow.webhook.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

interface WebhookEndpointRepository extends JpaRepository<WebhookEndpointEntity, UUID> {

    List<WebhookEndpointEntity> findByMerchantIdAndActiveTrue(UUID merchantId);
}