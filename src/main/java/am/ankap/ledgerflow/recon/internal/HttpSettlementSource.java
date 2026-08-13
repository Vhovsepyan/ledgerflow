package am.ankap.ledgerflow.recon.internal;

import am.ankap.ledgerflow.recon.SettlementLine;
import am.ankap.ledgerflow.recon.SettlementSource;
import am.ankap.ledgerflow.shared.Money;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;

@Component
class HttpSettlementSource implements SettlementSource {

    private final RestClient restClient;

    HttpSettlementSource(@Value("${ledgerflow.psp.base-url:http://localhost:9090}") String baseUrl) {
        this.restClient = RestClient.create(baseUrl);
    }

    @Override
    public List<SettlementLine> linesFor(LocalDate settlementDate) {
        String csv = restClient.get()
                .uri(builder -> builder.path("/psp/settlements")
                        .queryParam("date", settlementDate.toString())
                        .build())
                .retrieve()
                .body(String.class);

        return parse(csv);
    }

    static List<SettlementLine> parse(String csv) {
        List<SettlementLine> lines = new ArrayList<>();
        if (csv == null || csv.isBlank()) {
            return lines;
        }

        String[] rows = csv.split("\\R");
        for (int i = 1; i < rows.length; i++) {   // skip the header
            String row = rows[i].trim();
            if (row.isEmpty()) {
                continue;
            }
            String[] cells = row.split(",");
            if (cells.length < 5) {
                throw new IllegalStateException("Malformed settlement row: " + row);
            }
            lines.add(new SettlementLine(
                    cells[0],
                    cells[1],
                    Money.of(Long.parseLong(cells[2]), Currency.getInstance(cells[3])),
                    LocalDate.parse(cells[4])));
        }
        return lines;
    }
}