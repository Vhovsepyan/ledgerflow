package am.ankap.ledgerflow.psp;

import am.ankap.ledgerflow.TestcontainersConfig;
import am.ankap.ledgerflow.shared.Money;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the provider adapter against a real (mock) provider over real HTTP.
 *
 * Tagged "manual" because it is slow by design — it waits out read timeouts and
 * trips a circuit breaker — and because its value is in the printed narrative as
 * much as the assertions. Excluded from `./gradlew test`; run it with
 * `./gradlew manualTest`.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
@Tag("manual")
class PspServiceManualTest {

    private static final MockPsp mockPsp = MockPsp.startOnRandomPort();
    private static final RestClient chaos = RestClient.create(mockPsp.baseUrl());

    @Autowired
    private PspService pspService;

    @Autowired
    private CircuitBreaker pspCircuitBreaker;

    @DynamicPropertySource
    static void pspBaseUrl(DynamicPropertyRegistry registry) {
        registry.add("ledgerflow.psp.base-url", mockPsp::baseUrl);
    }

    @AfterAll
    static void stopMockPsp() {
        mockPsp.stop();
    }

    /** The breaker and the RestClient are context-scoped singletons, so tests must not inherit each other's damage. */
    @BeforeEach
    void resetProviderState() {
        pspCircuitBreaker.reset();
        mockPsp.reset();
    }

    @Test
    void authorizesCleanlyWhenTheProviderBehaves() {
        setChaos(0, 0, 0);
        String reference = "manual-" + UUID.randomUUID();

        PspCall result = pspService.authorize(reference, Money.parse("50.00", "USD"), reference);

        System.out.println("Result: " + result);
        assertThat(result.result()).isInstanceOf(PspResult.Authorized.class);
    }

    @Test
    void returnsUnknownWhenEveryAttemptTimesOut() {
        setChaos(0, 0, 1.0);   // every call takes 8s, our read timeout is 2s
        String reference = "manual-" + UUID.randomUUID();

        long start = System.currentTimeMillis();
        PspCall result = pspService.authorize(reference, Money.parse("50.00", "USD"), reference);
        long elapsed = System.currentTimeMillis() - start;

        System.out.println("Result after " + elapsed + "ms: " + result);
        assertThat(result.result()).isInstanceOf(PspResult.Unknown.class);

        // The provider DID authorize it - we just never heard the answer.
        setChaos(0, 0, 0);
        PspCall truth = pspService.lookupByReference(reference);
        System.out.println("What actually happened: " + truth);
        assertThat(truth.result()).isInstanceOf(PspResult.Authorized.class);
    }

    @Test
    void opensTheCircuitBreakerAfterRepeatedErrors() {
        setChaos(0, 1.0, 0);   // every call returns 500

        for (int i = 0; i < 10; i++) {
            String reference = "manual-" + UUID.randomUUID();
            PspCall result = pspService.authorize(reference, Money.parse("50.00", "USD"), reference);
            System.out.println(i + " -> " + result);
        }

        String reference = "manual-" + UUID.randomUUID();
        PspCall result = pspService.authorize(reference, Money.parse("50.00", "USD"), reference);
        System.out.println("After breaker opens: " + result);

        assertThat(result.result()).isInstanceOf(PspResult.Failed.class);
        setChaos(0, 0, 0);
    }

    private static void setChaos(double decline, double error, double timeout) {
        chaos.post().uri("/admin/chaos")
                .header("Content-Type", "application/json")
                .body("""
                      {"declineRate": %s, "errorRate": %s, "timeoutRate": %s}
                      """.formatted(decline, error, timeout))
                .retrieve()
                .toBodilessEntity();
    }
}
