package am.ankap.ledgerflow.recon.internal;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Reports on the books, not on the process.
 *
 * Two things can be wrong: mismatches nobody has looked at, and reconciliation
 * not having run at all. The second is worse, because it looks like silence.
 */
@Component
class ReconHealthIndicator implements HealthIndicator {

    private static final long OPEN_MISMATCH_WARNING_THRESHOLD = 10;
    private static final Duration STALE_AFTER = Duration.ofHours(36);

    private final JdbcClient jdbcClient;

    ReconHealthIndicator(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Health health() {
        long openMismatches = jdbcClient
                .sql("select count(*) from recon_mismatch where status = 'OPEN'")
                .query(Long.class)
                .single();

        Optional<Instant> lastRun = jdbcClient
                .sql("select max(finished_at) from recon_run where status = 'COMPLETED'")
                .query(Instant.class)
                .optional();

        Map<String, Object> details = Map.of(
                "openMismatches", openMismatches,
                "lastCompletedRun", lastRun.map(Instant::toString).orElse("never"));

        boolean stale = lastRun.isEmpty()
                || lastRun.get().isBefore(Instant.now().minus(STALE_AFTER));

        if (stale) {
            return Health.down().withDetails(details)
                    .withDetail("reason", "Reconciliation has not completed recently").build();
        }
        if (openMismatches >= OPEN_MISMATCH_WARNING_THRESHOLD) {
            return Health.status("WARN").withDetails(details).build();
        }
        return Health.up().withDetails(details).build();
    }
}