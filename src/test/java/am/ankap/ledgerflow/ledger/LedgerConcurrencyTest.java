package am.ankap.ledgerflow.ledger;

import am.ankap.ledgerflow.TestcontainersConfig;
import am.ankap.ledgerflow.shared.Money;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestcontainersConfig.class)
class LedgerConcurrencyTest {

    private static final Currency USD = Currency.getInstance("USD");
    private static final int POST_COUNT = 100;
    private static final long AMOUNT_MINOR = 100L;

    @Autowired
    private LedgerService ledgerService;

    @Test
    void concurrentPostsProduceAnExactBalance() throws InterruptedException {
        String suffix = UUID.randomUUID().toString();
        String clearing = "PSP_CLEARING:USD:" + suffix;
        String payable = "MERCHANT_PAYABLE:conc:USD:" + suffix;

        ledgerService.openAccount(clearing, AccountType.ASSET, USD);
        ledgerService.openAccount(payable, AccountType.LIABILITY, USD);

        CountDownLatch startSignal = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(POST_COUNT);
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < POST_COUNT; i++) {
                int index = i;
                executor.submit(() -> {
                    try {
                        startSignal.await();
                        ledgerService.post(LedgerTransactionRequest
                                .reference("conc:%s:%d".formatted(suffix, index))
                                .description("Concurrent post " + index)
                                .debit(clearing, Money.of(AMOUNT_MINOR, "USD"))
                                .credit(payable, Money.of(AMOUNT_MINOR, "USD"))
                                .build());
                    } catch (Throwable t) {
                        failures.add(t);
                    } finally {
                        finished.countDown();
                    }
                });
            }
            startSignal.countDown();
            assertThat(finished.await(60, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(failures).isEmpty();
        assertThat(ledgerService.balanceOf(clearing))
                .isEqualTo(Money.of(AMOUNT_MINOR * POST_COUNT, "USD"));
        assertThat(ledgerService.balanceOf(payable))
                .isEqualTo(Money.of(-AMOUNT_MINOR * POST_COUNT, "USD"));
    }
}