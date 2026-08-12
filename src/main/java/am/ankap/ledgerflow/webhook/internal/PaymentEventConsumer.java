package am.ankap.ledgerflow.webhook.internal;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@Component
class PaymentEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventConsumer.class);

    private final WebhookEndpointRepository endpointRepository;
    private final WebhookDeliveryRepository deliveryRepository;
    private final ObjectMapper objectMapper;

    PaymentEventConsumer(WebhookEndpointRepository endpointRepository,
                         WebhookDeliveryRepository deliveryRepository,
                         ObjectMapper objectMapper) {
        this.endpointRepository = endpointRepository;
        this.deliveryRepository = deliveryRepository;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "${ledgerflow.kafka.payment-events-topic:payment-events}",
            groupId = "webhook-dispatcher")
    @Transactional
    void onPaymentEvent(ConsumerRecord<String, String> record) {
        UUID eventId = UUID.fromString(headerOf(record, "event-id"));
        String eventType = headerOf(record, "event-type");
        String payload = record.value();

        JsonNode json = objectMapper.readTree(payload);
        UUID merchantId = UUID.fromString(json.get("merchantId").asString());

        List<WebhookEndpointEntity> endpoints =
                endpointRepository.findByMerchantIdAndActiveTrue(merchantId);

        for (WebhookEndpointEntity endpoint : endpoints) {
            try {
                deliveryRepository.saveAndFlush(new WebhookDeliveryEntity(
                        endpoint.getId(), eventId, eventType, payload));
            } catch (DataIntegrityViolationException alreadyQueued) {
                // At-least-once delivery: we have seen this event before.
                log.debug("Delivery already queued for event {} endpoint {}", eventId, endpoint.getId());
            }
        }
    }

    private static String headerOf(ConsumerRecord<String, String> record, String key) {
        return new String(record.headers().lastHeader(key).value(), StandardCharsets.UTF_8);
    }
}