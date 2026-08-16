package am.ankap.ledgerflow.recon.internal;

import am.ankap.ledgerflow.recon.ReconResult;
import am.ankap.ledgerflow.recon.ReconciliationService;
import am.ankap.ledgerflow.recon.SettlementLine;
import am.ankap.ledgerflow.recon.SettlementSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Orchestrates a reconciliation run. Deliberately not transactional.
 *
 * The database work lives in {@link ReconWriter}; what happens here is the
 * fetch, which is an HTTP call to the provider. Holding a transaction open
 * across it would park a pooled connection on another company's response time
 * and pin the oldest-transaction horizon while it waited.
 */
@Service
class DefaultReconciliationService implements ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(DefaultReconciliationService.class);

    private final SettlementSource settlementSource;
    private final ReconWriter reconWriter;
    private final ReconMetrics reconMetrics;

    DefaultReconciliationService(SettlementSource settlementSource,
                                 ReconWriter reconWriter,
                                 ReconMetrics reconMetrics) {
        this.settlementSource = settlementSource;
        this.reconWriter = reconWriter;
        this.reconMetrics = reconMetrics;
    }

    @Override
    public ReconResult reconcile(LocalDate settlementDate) {
        UUID runId = reconWriter.startRun(settlementDate);

        try {
            // No transaction is open here, on purpose.
            List<SettlementLine> statement = settlementSource.linesFor(settlementDate);

            ReconResult result = reconWriter.compareAndRecord(runId, settlementDate, statement);
            reconWriter.completeRun(runId, result);
            reconMetrics.refresh();

            log.info("Reconciliation {} for {}: {} lines, {} matched, {} mismatched, {} pending",
                    runId, settlementDate, result.linesRead(),
                    result.matched(), result.mismatched(), result.pendingTiming());

            return result;

        } catch (RuntimeException e) {
            reconWriter.failRun(runId, e.getMessage());
            log.error("Reconciliation for {} failed", settlementDate, e);
            throw e;
        }
    }
}
