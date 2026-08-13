package am.ankap.ledgerflow.recon.internal;

import am.ankap.ledgerflow.recon.ReconResult;
import am.ankap.ledgerflow.recon.ReconciliationService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin/reconciliation")
class ReconController {

    private final ReconciliationService reconciliationService;
    private final JdbcClient jdbcClient;

    ReconController(ReconciliationService reconciliationService, JdbcClient jdbcClient) {
        this.reconciliationService = reconciliationService;
        this.jdbcClient = jdbcClient;
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
                        select reference, mismatch_type, provider_amount_minor, ledger_amount_minor,
                               currency, suggestion, evidence, created_at
                          from recon_mismatch
                         where status = 'OPEN'
                         order by created_at desc
                         limit 100
                        """)
                .query()
                .listOfRows();
    }
}