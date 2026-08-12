package am.ankap.ledgerflow.payment;

import am.ankap.ledgerflow.TestcontainersConfig;
import am.ankap.ledgerflow.psp.FakePspConfig;
import am.ankap.ledgerflow.psp.FakePspService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

@SpringBootTest(webEnvironment = RANDOM_PORT)
@AutoConfigureRestTestClient
@Import({ TestcontainersConfig.class, FakePspConfig.class })
@TestPropertySource(properties = "ledgerflow.kafka.enabled=false")
class PaymentIdempotencyConcurrencyTest {

    private static final int PARALLEL_REQUESTS = 20;

    @Autowired
    private RestTestClient restClient;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private FakePspService fakePsp;

    @BeforeEach
    void setUp() {
        fakePsp.reset();
    }

    @Test
    void concurrentIdenticalRequestsCreateExactlyOnePayment() throws InterruptedException {
        UUID merchantId = MerchantFixture.createMerchant(jdbcClient);
        String key = UUID.randomUUID().toString();

        CountDownLatch startSignal = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(PARALLEL_REQUESTS);
        List<Integer> statuses = new CopyOnWriteArrayList<>();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < PARALLEL_REQUESTS; i++) {
                executor.submit(() -> {
                    try {
                        startSignal.await();
                        int status = restClient.post().uri("/v1/payments")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("X-Merchant-Id", merchantId.toString())
                                .header("Idempotency-Key", key)
                                .body("""
                                      {"amountMinor": 5000, "currency": "USD", "merchantRef": "race"}
                                      """)
                                .exchange()
                                .returnResult()
                                .getStatus()
                                .value();
                        statuses.add(status);
                    } catch (Exception e) {
                        statuses.add(-1);
                    } finally {
                        finished.countDown();
                    }
                });
            }
            startSignal.countDown();
            assertThat(finished.await(60, TimeUnit.SECONDS)).isTrue();
        }

        long paymentCount = jdbcClient.sql("select count(*) from payment where merchant_id = :id")
                .param("id", merchantId)
                .query(Long.class)
                .single();

        assertThat(paymentCount).isEqualTo(1);
        assertThat(statuses).hasSize(PARALLEL_REQUESTS);
        assertThat(statuses).allMatch(status -> status == 201 || status == 409);
        assertThat(statuses).contains(201);
    }
}