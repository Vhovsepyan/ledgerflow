package am.ankap.ledgerflow.recon.internal;

import am.ankap.ledgerflow.recon.ReconciliationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Component
class ReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationJob.class);

    private final ReconciliationService reconciliationService;

    ReconciliationJob(ReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    /** Runs nightly. Reconciles the previous day, once the provider has published it. */
    @Scheduled(cron = "${ledgerflow.recon.cron:0 0 2 * * *}")
    void reconcileYesterday() {
        try {
            reconciliationService.reconcile(LocalDate.now().minusDays(1));
        } catch (RuntimeException e) {
            log.error("Scheduled reconciliation failed", e);
        }
    }
}