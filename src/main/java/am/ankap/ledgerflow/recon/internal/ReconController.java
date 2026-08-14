package am.ankap.ledgerflow.recon.internal;

import am.ankap.ledgerflow.recon.ReconResult;
import am.ankap.ledgerflow.recon.ReconciliationService;
import jakarta.validation.Valid;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/admin/reconciliation")
class ReconController {

    private final ReconciliationService reconciliationService;
    private final JdbcClient jdbcClient;
    private final ReconMismatchRepository mismatchRepository;

    ReconController(ReconciliationService reconciliationService, JdbcClient jdbcClient, ReconMismatchRepository mismatchRepository) {
        this.reconciliationService = reconciliationService;
        this.jdbcClient = jdbcClient;
        this.mismatchRepository = mismatchRepository;
    }

    /** Runs reconciliation on demand, for a given date. */
    @PostMapping("/runs")
    ReconResult run(@RequestParam(required = false) String date) {
        LocalDate settlementDate = date == null ? LocalDate.now() : LocalDate.parse(date);
        return reconciliationService.reconcile(settlementDate);
    }

    @GetMapping("/runs")
    List<Map<String, Object>> recentRuns() {
        return jdbcClient.sql("""
                        select settlement_date, status, lines_read, matched, mismatched,
                               pending_timing, started_at, finished_at, error
                          from recon_run
                         order by started_at desc
                         limit 20
                        """)
                .query()
                .listOfRows();
    }

    @GetMapping("/mismatches")
    List<Map<String, Object>> openMismatches() {
        return jdbcClient.sql("""
                        select id, reference, mismatch_type, provider_amount_minor,
                               ledger_amount_minor, currency, suggestion, evidence, created_at
                          from recon_mismatch
                         where status = 'OPEN'
                         order by created_at desc
                         limit 100
                        """)
                .query()
                .listOfRows();
    }

    /**
     * Records a human's decision about a mismatch.
     * Note this does not adjust the ledger — a correcting entry, if one is
     * needed, is posted deliberately and separately.
     */
    @PostMapping("/mismatches/{mismatchId}/resolve")
    @Transactional
    Map<String, Object> resolve(@PathVariable UUID mismatchId,
                                @Valid @RequestBody ResolveMismatchRequest request) {

        ReconMismatchEntity mismatch = mismatchRepository.findById(mismatchId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown mismatch " + mismatchId));

        mismatch.resolve(request.status(), request.resolvedBy(), request.note());

        return Map.of("id", mismatchId, "status", request.status().name());
    }
}