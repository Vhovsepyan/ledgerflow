package am.ankap.ledgerflow.recon.internal;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

@Component
class ReconMetrics {

    private final AtomicLong openMismatches = new AtomicLong();
    private final ReconMismatchRepository mismatchRepository;

    ReconMetrics(MeterRegistry registry, ReconMismatchRepository mismatchRepository) {
        this.mismatchRepository = mismatchRepository;

        registry.gauge("ledgerflow.recon.open_mismatches", openMismatches);
    }

    @Scheduled(fixedDelayString = "${ledgerflow.metrics.refresh-interval:15s}")
    void refresh() {
        openMismatches.set(mismatchRepository.countByStatus("OPEN"));
    }
}