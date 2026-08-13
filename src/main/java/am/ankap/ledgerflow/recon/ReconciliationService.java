package am.ankap.ledgerflow.recon;

import java.time.LocalDate;

public interface ReconciliationService {

    /**
     * Compares the provider's settlement statement against the ledger.
     * Records what it finds; never corrects the ledger by itself.
     */
    ReconResult reconcile(LocalDate settlementDate);
}