package am.ankap.ledgerflow.recon;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * A settlement statement you control line by line, so every mismatch
 * type can be produced deliberately instead of by chance.
 */
public class FakeSettlementSource implements SettlementSource {

    private final List<SettlementLine> lines = new ArrayList<>();

    @Override
    public List<SettlementLine> linesFor(LocalDate settlementDate) {
        return List.copyOf(lines);
    }

    public void add(SettlementLine line) {
        lines.add(line);
    }

    public void reset() {
        lines.clear();
    }
}