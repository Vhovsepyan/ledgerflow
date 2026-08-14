package am.ankap.ledgerflow.recon.internal;

import am.ankap.ledgerflow.recon.MismatchType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Builds the evidence a human needs to judge a mismatch, from the provider
 * attempt log. It explains; it never decides.
 */
@Component
class EvidenceCollector {

    private final JdbcClient jdbcClient;

    EvidenceCollector(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    Evidence forPayment(UUID paymentId, MismatchType type) {
        if (paymentId == null || type == MismatchType.MISSING_IN_LEDGER) {
            List<Map<String, Object>> attempts = paymentId == null ? List.of() : attemptsFor(paymentId);
            if (attempts.isEmpty()) {
                return new Evidence(
                        "No payment and no provider calls exist on our side for this reference.",
                        "The provider settled something we have no record of. Check whether the "
                                + "reference belongs to another environment, or whether a payment was lost "
                                + "before it was stored.");
            }
        }

        List<Map<String, Object>> attempts = attemptsFor(paymentId);
        if (attempts.isEmpty()) {
            return new Evidence("No provider calls recorded for this payment.",
                    "The ledger has a capture but no provider attempt exists. This should not "
                            + "happen; investigate whether the attempt log failed to write.");
        }

        String story = attempts.stream()
                .map(row -> "%s: %s after %s attempt(s), %sms%s".formatted(
                        row.get("created_at"),
                        row.get("outcome"),
                        row.get("attempts"),
                        row.get("latency_ms"),
                        row.get("detail") == null ? "" : " (" + row.get("detail") + ")"))
                .collect(Collectors.joining("\n"));

        boolean hadUnknown = attempts.stream()
                .anyMatch(row -> "UNKNOWN".equals(row.get("outcome")));

        String suggestion = hadUnknown
                ? "This payment had an unresolved provider call that was later verified. "
                  + "The provider's figure is likely correct; confirm before adjusting."
                : "All provider calls returned a definite answer. A difference here suggests a "
                  + "genuine discrepancy rather than a timing or retry artefact.";

        return new Evidence(story, suggestion);
    }

    private List<Map<String, Object>> attemptsFor(UUID paymentId) {
        return jdbcClient.sql("""
                        select operation, attempts, outcome, latency_ms, detail, created_at
                          from psp_attempt
                         where payment_id = :id
                         order by created_at
                        """)
                .param("id", paymentId)
                .query()
                .listOfRows();
    }

    record Evidence(String story, String suggestion) {
    }
}