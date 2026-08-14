package am.ankap.ledgerflow.webhook.internal;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

@Component
class WebhookMetrics {

    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong dead = new AtomicLong();
    private final JdbcClient jdbcClient;

    WebhookMetrics(MeterRegistry registry, JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
        registry.gauge("ledgerflow.webhooks.pending", pending);
        registry.gauge("ledgerflow.webhooks.dead", dead);
    }

    @Scheduled(fixedDelayString = "${ledgerflow.metrics.refresh-interval:15s}")
    void refresh() {
        pending.set(count("PENDING"));
        dead.set(count("DEAD"));
    }

    private long count(String status) {
        return jdbcClient.sql("select count(*) from webhook_delivery where status = :status")
                .param("status", status)
                .query(Long.class)
                .single();
    }
}