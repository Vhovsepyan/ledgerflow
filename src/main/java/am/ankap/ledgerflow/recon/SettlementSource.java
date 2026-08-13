package am.ankap.ledgerflow.recon;

import java.time.LocalDate;
import java.util.List;

public interface SettlementSource {

    List<SettlementLine> linesFor(LocalDate settlementDate);
}