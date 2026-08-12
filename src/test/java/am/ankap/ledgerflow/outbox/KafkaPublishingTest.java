package am.ankap.ledgerflow.outbox;

import am.ankap.ledgerflow.KafkaTestcontainersConfig;
import am.ankap.ledgerflow.TestcontainersConfig;
import am.ankap.ledgerflow.payment.MerchantFixture;
import am.ankap.ledgerflow.psp.FakePspConfig;
import am.ankap.ledgerflow.psp.FakePspService;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

@SpringBootTest(webEnvironment = RANDOM_PORT)
@AutoConfigureRestTestClient
@Import({ TestcontainersConfig.class, KafkaTestcontainersConfig.class, FakePspConfig.class })
class KafkaPublishingTest {

    @Autowired
    private RestTestClient restClient;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private FakePspService fakePsp;

    @Autowired
    private ConsumerFactory<String, String> consumerFactory;

    private UUID merchantId;

    @BeforeEach
    void setUp() {
        merchantId = MerchantFixture.createMerchant(jdbcClient);
        fakePsp.reset();
    }

    @Test
    void paymentEventsReachKafkaInOrderOnOnePartition() {
        String paymentId = createPayment();

        restClient.post().uri("/v1/payments/{id}/authorize", paymentId).exchange().expectStatus().isOk();
        restClient.post().uri("/v1/payments/{id}/capture", paymentId).exchange().expectStatus().isOk();

        List<ConsumerRecord<String, String>> received = consumeEventsFor(paymentId, 2);

        assertThat(received).hasSize(2);
        assertThat(headerOf(received.get(0), "event-type")).isEqualTo("payment.authorized");
        assertThat(headerOf(received.get(1), "event-type")).isEqualTo("payment.captured");

        // Same key means same partition, which is what guarantees the order above.
        assertThat(received.get(0).key()).isEqualTo(paymentId);
        assertThat(received.get(0).partition()).isEqualTo(received.get(1).partition());

        assertThat(received.get(1).value()).contains("\"merchantNetMinor\":4825");
    }

    private List<ConsumerRecord<String, String>> consumeEventsFor(String paymentId, int expected) {
        List<ConsumerRecord<String, String>> matching = new ArrayList<>();

        try (Consumer<String, String> consumer = consumerFactory.createConsumer(
                "test-" + UUID.randomUUID(), "")) {
            consumer.subscribe(List.of("payment-events"));

            Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                ConsumerRecords<String, String> polled = consumer.poll(Duration.ofMillis(500));
                polled.forEach(record -> {
                    if (paymentId.equals(record.key())) {
                        matching.add(record);
                    }
                });
                assertThat(matching).hasSizeGreaterThanOrEqualTo(expected);
            });
        }
        return matching;
    }

    private static String headerOf(ConsumerRecord<String, String> record, String key) {
        return new String(record.headers().lastHeader(key).value(), StandardCharsets.UTF_8);
    }

    private String createPayment() {
        byte[] response = restClient.post().uri("/v1/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Merchant-Id", merchantId.toString())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .body("""
                      {"amountMinor": 5000, "currency": "USD", "merchantRef": "kafka-1"}
                      """)
                .exchange()
                .expectStatus().isCreated()
                .expectBody().returnResult().getResponseBody();

        String json = new String(response);
        int start = json.indexOf("\"id\":\"") + 6;
        return json.substring(start, json.indexOf('"', start));
    }
}