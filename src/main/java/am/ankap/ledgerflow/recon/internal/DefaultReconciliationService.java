package am.ankap.ledgerflow.recon.internal;

import am.ankap.ledgerflow.ledger.CapturedAmount;
import am.ankap.ledgerflow.ledger.LedgerService;
import am.ankap.ledgerflow.recon.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

@Service
class DefaultReconciliationService implements ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(DefaultReconciliationService.class);

    /**
     * A capture younger than this is not expected in today's statement yet.
     * This is a timing assumption about the provider, not a fact — if it is wrong,
     * real problems get filed as "pending" and stay invisible.
     */
    private static final Duration SETTLEMENT_LAG = Duration.ofHours(24);

    private final SettlementSource settlementSource;
    private final LedgerService ledgerService;
    private final ReconRunRepository runRepository;
    private final ReconMismatchRepository mismatchRepository;
    private final EvidenceCollector evidenceCollector;
    private final JdbcClient jdbcClient;
    private final SettlementPoster settlementPoster;

    DefaultReconciliationService(SettlementSource settlementSource,
                                 LedgerService ledgerService,
                                 ReconRunRepository runRepository,
                                 ReconMismatchRepository mismatchRepository,
                                 EvidenceCollector evidenceCollector,
                                 JdbcClient jdbcClient, SettlementPoster settlementPoster) {
        this.settlementSource = settlementSource;
        this.ledgerService = ledgerService;
        this.runRepository = runRepository;
        this.mismatchRepository = mismatchRepository;
        this.evidenceCollector = evidenceCollector;
        this.jdbcClient = jdbcClient;
        this.settlementPoster = settlementPoster;
    }

    @Override
    @Transactional
    public ReconResult reconcile(LocalDate settlementDate) {
        ReconRunEntity run = runRepository.save(new ReconRunEntity(settlementDate));

        try {
            List<SettlementLine> statement = settlementSource.linesFor(settlementDate);
            Map<UUID, CapturedAmount> ledgerByPayment = new HashMap<>();
            ledgerService.capturedAmounts().forEach(c -> ledgerByPayment.put(c.sourceId(), c));

            List<SettlementLine> matchedLines = new ArrayList<>();

            int matched = 0;
            int mismatched = 0;

            for (SettlementLine line : statement) {
                UUID paymentId = paymentIdOf(line.reference());
                CapturedAmount ledgerSide = paymentId == null ? null : ledgerByPayment.remove(paymentId);

                if (ledgerSide == null) {
                    recordMismatch(run.getId(), paymentId, line.reference(),
                            MismatchType.MISSING_IN_LEDGER,
                            line.amount().minorUnits(), null,
                            line.amount().currency().getCurrencyCode());
                    mismatched++;

                } else if (ledgerSide.amountMinor() != line.amount().minorUnits()) {
                    recordMismatch(run.getId(), paymentId, line.reference(),
                            MismatchType.AMOUNT_MISMATCH,
                            line.amount().minorUnits(), ledgerSide.amountMinor(),
                            line.amount().currency().getCurrencyCode());
                    mismatched++;

                } else {
                    matched++;
                    matchedLines.add(line);
                }
            }
            settlementPoster.postSettlements(run.getId(), settlementDate, matchedLines);

            // Anything left in the map is captured on our side but absent from the statement.
            int pendingTiming = 0;
            for (CapturedAmount unsettled : ledgerByPayment.values()) {
                if (isWithinSettlementLag(unsettled.sourceId())) {
                    pendingTiming++;   // normal delay, not a discrepancy
                } else {
                    recordMismatch(run.getId(), unsettled.sourceId(),
                            "payment-" + unsettled.sourceId(),
                            MismatchType.MISSING_IN_PROVIDER,
                            null, unsettled.amountMinor(),
                            unsettled.currency().getCurrencyCode());
                    mismatched++;
                }
            }

            run.complete(statement.size(), matched, mismatched, pendingTiming);
            log.info("Reconciliation {} for {}: {} lines, {} matched, {} mismatched, {} pending",
                    run.getId(), settlementDate, statement.size(), matched, mismatched, pendingTiming);

            return new ReconResult(run.getId(), settlementDate,
                    statement.size(), matched, mismatched, pendingTiming);

        } catch (RuntimeException e) {
            run.fail(e.getMessage());
            log.error("Reconciliation for {} failed", settlementDate, e);
            throw e;
        }
    }

    private void recordMismatch(UUID runId, UUID paymentId, String reference, MismatchType type,
                                Long providerAmount, Long ledgerAmount, String currency) {
        EvidenceCollector.Evidence evidence = evidenceCollector.forPayment(paymentId);

        mismatchRepository.save(new ReconMismatchEntity(
                runId, paymentId, reference, type,
                providerAmount, ledgerAmount, currency,
                evidence.story(), evidence.suggestion()));

        log.warn("Reconciliation mismatch {} for {}: provider={} ledger={}",
                type, reference, providerAmount, ledgerAmount);
    }

    private boolean isWithinSettlementLag(UUID paymentId) {
        Instant capturedAt = jdbcClient
                .sql("select updated_at from payment where id = :id and status = 'CAPTURED'")
                .param("id", paymentId)
                .query(Instant.class)
                .optional()
                .orElse(null);

        return capturedAt != null && capturedAt.isAfter(Instant.now().minus(SETTLEMENT_LAG));
    }

    /** References are written by the payment module as payment-{uuid}. */
    private static UUID paymentIdOf(String reference) {
        if (reference == null || !reference.startsWith("payment-")) {
            return null;
        }
        try {
            return UUID.fromString(reference.substring("payment-".length()));
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }
}