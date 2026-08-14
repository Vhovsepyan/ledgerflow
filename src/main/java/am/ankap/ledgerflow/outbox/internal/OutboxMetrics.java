package am.ankap.ledgerflow.outbox.internal;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Outbox lag is the single most useful number here: if it grows, events are
 * being written but not published, and every downstream consumer is blind.
 */
@Component
class OutboxMetrics {

    private final AtomicLong unpublished = new AtomicLong();
    private final AtomicLong oldestAgeSeconds = new AtomicLong();
    private final JdbcClient jdbcClient;

    OutboxMetrics(MeterRegistry registry, JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
        registry.gauge("ledgerflow.outbox.unpublished", unpublished);
        registry.gauge("ledgerflow.outbox.oldest_unpublished_seconds", oldestAgeSeconds);
    }

    @Scheduled(fixedDelayString = "${ledgerflow.metrics.refresh-interval:15s}")
    void refresh() {
        unpublished.set(jdbcClient
                .sql("select count(*) from outbox_event where published_at is null")
                .query(Long.class)
                .single());

        oldestAgeSeconds.set(jdbcClient
                .sql("""
                     select coalesce(extract(epoch from now() - min(created_at)), 0)::bigint
                       from outbox_event where published_at is null
                     """)
                .query(Long.class)
                .single());
    }
}