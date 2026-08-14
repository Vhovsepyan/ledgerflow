package am.ankap.ledgerflow.payment.internal;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Gauges read a number; they do not observe events. Something has to look.
 */
@Component
class PaymentGaugeUpdater {

    private final JdbcClient jdbcClient;
    private final PaymentMetrics metrics;

    PaymentGaugeUpdater(JdbcClient jdbcClient, PaymentMetrics metrics) {
        this.jdbcClient = jdbcClient;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${ledgerflow.metrics.refresh-interval:15s}")
    void refresh() {
        Long pending = jdbcClient.sql("""
                        select count(*) from payment
                         where status in ('AUTHORIZATION_PENDING', 'CAPTURE_PENDING')
                        """)
                .query(Long.class)
                .single();

        metrics.setPendingPayments(pending);
    }
}