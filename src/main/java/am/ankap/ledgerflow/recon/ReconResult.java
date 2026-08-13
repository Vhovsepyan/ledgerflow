package am.ankap.ledgerflow.recon;

import java.time.LocalDate;
import java.util.UUID;

public record ReconResult(
        UUID runId,
        LocalDate settlementDate,
        int linesRead,
        int matched,
        int mismatched,
        int pendingTiming) {
}