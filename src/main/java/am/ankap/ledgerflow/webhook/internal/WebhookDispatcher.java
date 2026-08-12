package am.ankap.ledgerflow.webhook.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Component
class WebhookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);
    private static final int BATCH_SIZE = 20;
    private static final int MAX_ATTEMPTS = 8;

    private final WebhookDeliveryRepository deliveryRepository;
    private final WebhookEndpointRepository endpointRepository;
    private final RestClient webhookRestClient;

    WebhookDispatcher(WebhookDeliveryRepository deliveryRepository,
                      WebhookEndpointRepository endpointRepository,
                      RestClient webhookRestClient) {
        this.deliveryRepository = deliveryRepository;
        this.endpointRepository = endpointRepository;
        this.webhookRestClient = webhookRestClient;
    }

    @Scheduled(fixedDelayString = "${ledgerflow.webhook.poll-interval:2s}")
    @Transactional
    void dispatchDueDeliveries() {
        List<WebhookDeliveryEntity> due =
                deliveryRepository.lockDueDeliveries(Instant.now(), BATCH_SIZE);

        for (WebhookDeliveryEntity delivery : due) {
            endpointRepository.findById(delivery.getEndpointId())
                    .ifPresent(endpoint -> attempt(delivery, endpoint));
        }
    }

    private void attempt(WebhookDeliveryEntity delivery, WebhookEndpointEntity endpoint) {
        long timestamp = Instant.now().getEpochSecond();
        String signature = WebhookSigner.sign(endpoint.getSecret(), timestamp, delivery.getPayload());

        try {
            HttpStatusCode status = webhookRestClient.post()
                    .uri(endpoint.getUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Ledgerflow-Event-Id", delivery.getEventId().toString())
                    .header("X-Ledgerflow-Event-Type", delivery.getEventType())
                    .header("X-Ledgerflow-Timestamp", String.valueOf(timestamp))
                    .header("X-Ledgerflow-Signature", "v1=" + signature)
                    .body(delivery.getPayload())
                    .retrieve()
                    .onStatus(code -> true, (request, response) -> { })
                    .toBodilessEntity()
                    .getStatusCode();

            if (status.is2xxSuccessful()) {
                delivery.markDelivered(status.value());
            } else if (status.is4xxClientError() && status.value() != 429) {
                // The endpoint understood us and said no. Retrying will not help.
                delivery.markDead(status.value(), "Client error, not retryable");
                log.warn("Webhook delivery {} dead: endpoint returned {}", delivery.getId(), status.value());
            } else {
                retryOrGiveUp(delivery, status.value(), "HTTP " + status.value());
            }

        } catch (RuntimeException e) {
            retryOrGiveUp(delivery, null, e.getMessage());
        }
    }

    private void retryOrGiveUp(WebhookDeliveryEntity delivery, Integer httpStatus, String error) {
        if (delivery.getAttempts() + 1 >= MAX_ATTEMPTS) {
            delivery.markDead(httpStatus, error);
            log.error("Webhook delivery {} dead after {} attempts: {}",
                    delivery.getId(), delivery.getAttempts() + 1, error);
        } else {
            delivery.markRetryable(httpStatus, error, Instant.now().plus(backoffFor(delivery.getAttempts() + 1)));
        }
    }

    /** 5s, 10s, 20s, 40s, 80s, 160s, 320s — capped at 10 minutes. */
    private static Duration backoffFor(int attempt) {
        Duration base = Duration.ofSeconds(5).multipliedBy(1L << Math.min(attempt - 1, 10));
        Duration max = Duration.ofMinutes(10);
        return base.compareTo(max) > 0 ? max : base;
    }
}