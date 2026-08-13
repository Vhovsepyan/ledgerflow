package am.ankap.ledgerflow.recon;

import am.ankap.ledgerflow.recon.internal.HttpSettlementSource;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettlementParsingTest {

    @Test
    void parsesAWellFormedStatement() {
        String csv = """
                     reference,psp_reference,amount_minor,currency,captured_at
                     payment-11111111-1111-1111-1111-111111111111,auth_a,5000,USD,2026-08-12
                     payment-22222222-2222-2222-2222-222222222222,auth_b,1200,JPY,2026-08-12
                     """;

        List<SettlementLine> lines = HttpSettlementSource.parse(csv);

        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).amount().minorUnits()).isEqualTo(5000L);
        assertThat(lines.get(1).amount().currency().getCurrencyCode()).isEqualTo("JPY");
        assertThat(lines.get(1).settledOn()).isEqualTo(LocalDate.of(2026, 8, 12));
    }

    @Test
    void ignoresTrailingBlankLines() {
        String csv = """
                     reference,psp_reference,amount_minor,currency,captured_at
                     payment-11111111-1111-1111-1111-111111111111,auth_a,5000,USD,2026-08-12

                     """;

        assertThat(HttpSettlementSource.parse(csv)).hasSize(1);
    }

    @Test
    void returnsNothingForAnEmptyStatement() {
        assertThat(HttpSettlementSource.parse("")).isEmpty();
        assertThat(HttpSettlementSource.parse(null)).isEmpty();
    }

    @Test
    void refusesAMalformedRowRatherThanGuessing() {
        String csv = """
                     reference,psp_reference,amount_minor,currency,captured_at
                     payment-11111111-1111-1111-1111-111111111111,auth_a,5000
                     """;

        assertThatThrownBy(() -> HttpSettlementSource.parse(csv))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Malformed");
    }
}