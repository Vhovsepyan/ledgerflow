package am.ankap.ledgerflow.outbox.internal;

import am.ankap.ledgerflow.outbox.EventPublisher;
import am.ankap.ledgerflow.outbox.OutboxRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
@Primary
@ConditionalOnProperty(name = "ledgerflow.kafka.enabled", havingValue = "true", matchIfMissing = true)
class KafkaEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventPublisher.class);

    /** Keeps the relay's locked transaction short even when the broker is slow. */
    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(5);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String topic;

    KafkaEventPublisher(KafkaTemplate<String, String> kafkaTemplate,
                        @Value("${ledgerflow.kafka.payment-events-topic:payment-events}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    @Override
    public void publish(OutboxRecord record) {
        ProducerRecord<String, String> message =
                new ProducerRecord<>(topic, record.partitionKey(), record.payload());

        message.headers()
                .add(header("event-id", record.id().toString()))
                .add(header("event-type", record.eventType()))
                .add(header("aggregate-type", record.aggregateType()))
                .add(header("aggregate-id", record.aggregateId().toString()))
                .add(header("occurred-at", record.createdAt().toString()));

        try {
            kafkaTemplate.send(message).get(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            log.debug("Published {} for payment {}", record.eventType(), record.aggregateId());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing " + record.id(), e);

        } catch (ExecutionException | TimeoutException e) {
            // Throwing leaves the row unpublished, so the relay retries it.
            throw new IllegalStateException("Failed to publish " + record.id(), e);
        }
    }

    private static RecordHeader header(String key, String value) {
        return new RecordHeader(key, value.getBytes(StandardCharsets.UTF_8));
    }
}