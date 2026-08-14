package am.ankap.ledgerflow.webhook.internal;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Turns published payment events into webhook delivery rows.
 *
 * This only records what needs sending — the dispatcher owns retry state, so a
 * slow merchant endpoint never holds up the consumer or the partition.
 */
@Component
class PaymentEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventConsumer.class);

    private final WebhookEndpointRepository endpointRepository;
    private final ObjectMapper objectMapper;
    private final JdbcClient jdbcClient;

    PaymentEventConsumer(WebhookEndpointRepository endpointRepository,
                         ObjectMapper objectMapper,
                         JdbcClient jdbcClient) {
        this.endpointRepository = endpointRepository;
        this.objectMapper = objectMapper;
        this.jdbcClient = jdbcClient;
    }

    @KafkaListener(
            topics = "${ledgerflow.kafka.payment-events-topic:payment-events}",
            groupId = "webhook-dispatcher")
    @Transactional
    void onPaymentEvent(ConsumerRecord<String, String> record) {
        restoreTraceContext(record);
        try {
            UUID eventId = UUID.fromString(headerOf(record, "event-id"));
            String eventType = headerOf(record, "event-type");
            String payload = record.value();

            JsonNode json = objectMapper.readTree(payload);
            UUID merchantId = UUID.fromString(json.get("merchantId").asString());

            List<WebhookEndpointEntity> endpoints =
                    endpointRepository.findByMerchantIdAndActiveTrue(merchantId);

            for (WebhookEndpointEntity endpoint : endpoints) {
                queueDelivery(endpoint.getId(), eventId, eventType, payload);
            }

            log.debug("Queued {} delivery/deliveries for event {}", endpoints.size(), eventId);

        } finally {
            // MDC is thread-local and the consumer thread is reused, so a value
            // left behind would contaminate the next message.
            MDC.remove("traceId");
            MDC.remove("spanId");
        }
    }

    /**
     * Kafka delivers at least once, so the same event will arrive more than once.
     * ON CONFLICT DO NOTHING absorbs the duplicate without raising — catching a
     * constraint violation would mark the transaction rollback-only and break
     * the remaining endpoints in this batch.
     */
    private void queueDelivery(UUID endpointId, UUID eventId, String eventType, String payload) {
        jdbcClient.sql("""
                       insert into webhook_delivery
                           (id, endpoint_id, event_id, event_type, payload,
                            status, attempts, next_retry_at, trace_id)
                       values (:id, :endpointId, :eventId, :eventType, :payload,
                               'PENDING', 0, now(), :traceId)
                       on conflict (endpoint_id, event_id) do nothing
                       """)
                .param("id", UUID.randomUUID())
                .param("endpointId", endpointId)
                .param("eventId", eventId)
                .param("eventType", eventType)
                .param("payload", payload)
                .param("traceId", MDC.get("traceId"))
                .update();
    }

    /** Restores the trace of the request that originally created this event. */
    private static void restoreTraceContext(ConsumerRecord<String, String> record) {
        String traceparent = headerOrNull(record, "traceparent");
        if (traceparent == null) {
            return;
        }
        String[] parts = traceparent.split("-");
        if (parts.length >= 3) {
            MDC.put("traceId", parts[1]);
            MDC.put("spanId", parts[2]);
        }
    }

    private static String headerOf(ConsumerRecord<String, String> record, String key) {
        return new String(record.headers().lastHeader(key).value(), StandardCharsets.UTF_8);
    }

    private static String headerOrNull(ConsumerRecord<String, String> record, String key) {
        var header = record.headers().lastHeader(key);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}