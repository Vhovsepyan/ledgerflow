package am.ankap.ledgerflow.webhook.internal;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "webhook_endpoint")
class WebhookEndpointEntity {

    @Id
    private UUID id;

    @Column(name = "merchant_id", nullable = false, updatable = false)
    private UUID merchantId;

    @Column(name = "url", nullable = false, length = 500)
    private String url;

    @Column(name = "secret", nullable = false, length = 255)
    private String secret;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    protected WebhookEndpointEntity() {
    }

    UUID getId() {
        return id;
    }

    String getUrl() {
        return url;
    }

    String getSecret() {
        return secret;
    }
}