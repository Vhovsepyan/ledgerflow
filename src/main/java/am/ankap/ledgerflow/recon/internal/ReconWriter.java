package am.ankap.ledgerflow.recon.internal;

import am.ankap.ledgerflow.ledger.CapturedAmount;
import am.ankap.ledgerflow.ledger.LedgerService;
import am.ankap.ledgerflow.recon.MismatchType;
import am.ankap.ledgerflow.recon.ReconResult;
import am.ankap.ledgerflow.recon.SettlementLine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Every database transaction reconciliation needs, and nothing else.
 *
 * These live in a bean of their own because the orchestration around them must
 * <em>not</em> be transactional: fetching the statement is an HTTP call to
 * another company, and a transaction held open across it holds row locks, a
 * pooled connection, and — worst — the oldest-transaction horizon that
 * autovacuum cannot clean past, database-wide. Splitting by private method
 * would not work: a self-call goes through {@code this} rather than the proxy,
 * so {@code @Transactional} would do nothing. The boundary has to be a bean
 * boundary.
 */
@Component
class ReconWriter {

    private static final Logger log = LoggerFactory.getLogger(ReconWriter.class);

    /**
     * A capture younger than this is not expected in today's statement yet.
     * This is a timing assumption about the provider, not a fact — if it is wrong,
     * real problems get filed as "pending" and stay invisible.
     */
    private static final Duration SETTLEMENT_LAG = Duration.ofHours(24);

    private final LedgerService ledgerService;
    private final ReconRunRepository runRepository;
    private final ReconMismatchRepository mismatchRepository;
    private final EvidenceCollector evidenceCollector;
    private final JdbcClient jdbcClient;
    private final SettlementPoster settlementPoster;

    ReconWriter(LedgerService ledgerService,
                ReconRunRepository runRepository,
                ReconMismatchRepository mismatchRepository,
                EvidenceCollector evidenceCollector,
                JdbcClient jdbcClient,
                SettlementPoster settlementPoster) {
        this.ledgerService = ledgerService;
        this.runRepository = runRepository;
        this.mismatchRepository = mismatchRepository;
        this.evidenceCollector = evidenceCollector;
        this.jdbcClient = jdbcClient;
        this.settlementPoster = settlementPoster;
    }

    /**
     * Records that a run started, and commits it immediately.
     *
     * REQUIRES_NEW matters: if this row were written in the same transaction as
     * the comparison, a failure would roll it back along with everything else,
     * and a run that failed would leave no trace that it was ever attempted —
     * which reads exactly like a night nobody ran reconciliation.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    UUID startRun(LocalDate settlementDate) {
        return runRepository.save(new ReconRunEntity(settlementDate)).getId();
    }

    /**
     * Compares one statement against the ledger and records what it finds.
     *
     * This is the part that must be atomic: mismatches and the settlement batch
     * describe the same judgement, and half of it committed would be worse than
     * neither.
     */
    @Transactional
    ReconResult compareAndRecord(UUID runId, LocalDate settlementDate, List<SettlementLine> statement) {
        Map<UUID, CapturedAmount> ledgerByPayment = new HashMap<>();
        ledgerService.capturedAmounts().forEach(c -> ledgerByPayment.put(c.sourceId(), c));

        List<SettlementLine> matchedLines = new ArrayList<>();

        int matched = 0;
        int mismatched = 0;

        for (SettlementLine line : statement) {
            UUID paymentId = paymentIdOf(line.reference());
            CapturedAmount ledgerSide = paymentId == null ? null : ledgerByPayment.remove(paymentId);

            if (ledgerSide == null) {
                recordMismatch(runId, paymentId, line.reference(),
                        MismatchType.MISSING_IN_LEDGER,
                        line.amount().minorUnits(), null,
                        line.amount().currency().getCurrencyCode());
                mismatched++;

            } else if (ledgerSide.amountMinor() != line.amount().minorUnits()) {
                recordMismatch(runId, paymentId, line.reference(),
                        MismatchType.AMOUNT_MISMATCH,
                        line.amount().minorUnits(), ledgerSide.amountMinor(),
                        line.amount().currency().getCurrencyCode());
                mismatched++;

            } else {
                matched++;
                matchedLines.add(line);
            }
        }
        settlementPoster.postSettlements(runId, settlementDate, matchedLines);

        // Anything left in the map is captured on our side but absent from the statement.
        int pendingTiming = 0;
        for (CapturedAmount unsettled : ledgerByPayment.values()) {
            if (isWithinSettlementLag(unsettled.sourceId())) {
                pendingTiming++;   // normal delay, not a discrepancy
            } else {
                recordMismatch(runId, unsettled.sourceId(),
                        "payment-" + unsettled.sourceId(),
                        MismatchType.MISSING_IN_PROVIDER,
                        null, unsettled.amountMinor(),
                        unsettled.currency().getCurrencyCode());
                mismatched++;
            }
        }

        return new ReconResult(runId, settlementDate,
                statement.size(), matched, mismatched, pendingTiming);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void completeRun(UUID runId, ReconResult result) {
        runRepository.findById(runId).ifPresent(run -> run.complete(
                result.linesRead(), result.matched(), result.mismatched(), result.pendingTiming()));
    }

    /**
     * Records the failure in its own transaction, so it survives the rollback of
     * whatever went wrong.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void failRun(UUID runId, String error) {
        runRepository.findById(runId).ifPresent(run -> run.fail(error));
    }

    private void recordMismatch(UUID runId, UUID paymentId, String reference, MismatchType type,
                                Long providerAmount, Long ledgerAmount, String currency) {

        boolean alreadyOpen = jdbcClient.sql("""
                        select count(*) from recon_mismatch
                         where reference = :reference and mismatch_type = :type and status = 'OPEN'
                        """)
                .param("reference", reference)
                .param("type", type.name())
                .query(Long.class)
                .single() > 0;

        if (alreadyOpen) {
            return;   // still unresolved from an earlier run
        }

        EvidenceCollector.Evidence evidence = evidenceCollector.forPayment(paymentId, type);

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
