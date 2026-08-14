package am.ankap.ledgerflow.payment.internal;

import am.ankap.ledgerflow.payment.PaymentStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

@Component
class PaymentMetrics {

    private final MeterRegistry registry;
    private final AtomicLong pendingPayments = new AtomicLong();

    PaymentMetrics(MeterRegistry registry) {
        this.registry = registry;
        registry.gauge("ledgerflow.payments.pending", pendingPayments);
    }

    /** One counter, tagged by outcome — success rate is a query, not a separate metric. */
    void recordOutcome(PaymentStatus status) {
        Counter.builder("ledgerflow.payments.outcome")
                .tag("status", status.name())
                .register(registry)
                .increment();
    }

    void recordProviderCall(String operation, String outcome, long latencyMs, int attempts) {
        Timer.builder("ledgerflow.psp.call")
                .tag("operation", operation)
                .tag("outcome", outcome)
                .register(registry)
                .record(java.time.Duration.ofMillis(latencyMs));

        registry.counter("ledgerflow.psp.attempts",
                "operation", operation, "outcome", outcome).increment(attempts);
    }

    void setPendingPayments(long count) {
        pendingPayments.set(count);
    }
}