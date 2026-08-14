package am.ankap.ledgerflow.recon.internal;

import am.ankap.ledgerflow.ledger.AccountType;
import am.ankap.ledgerflow.ledger.LedgerService;
import am.ankap.ledgerflow.ledger.LedgerTransactionRequest;
import am.ankap.ledgerflow.recon.SettlementLine;
import am.ankap.ledgerflow.shared.Money;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Records that the provider actually paid us.
 *
 * A provider settles in one lump sum, so this posts one ledger transaction per
 * currency per day — matching what the bank statement shows. Which payments were
 * in the batch is answered by recon_run and recon_mismatch, not by the ledger.
 */
@Component
class SettlementPoster {

    private static final Logger log = LoggerFactory.getLogger(SettlementPoster.class);

    private final LedgerService ledgerService;
    private final SettlementBatchRepository batchRepository;

    SettlementPoster(LedgerService ledgerService, SettlementBatchRepository batchRepository) {
        this.ledgerService = ledgerService;
        this.batchRepository = batchRepository;
    }

    /**
     * Posts one settlement transaction per currency for the matched lines.
     * Only matched lines settle — a disputed amount must not move money.
     */
    void postSettlements(UUID runId, LocalDate settlementDate, List<SettlementLine> matchedLines) {
        Map<Currency, Long> totalsByCurrency = new LinkedHashMap<>();
        Map<Currency, Integer> countsByCurrency = new LinkedHashMap<>();

        for (SettlementLine line : matchedLines) {
            totalsByCurrency.merge(line.amount().currency(), line.amount().minorUnits(), Long::sum);
            countsByCurrency.merge(line.amount().currency(), 1, Integer::sum);
        }

        totalsByCurrency.forEach((currency, total) ->
                postOneCurrency(runId, settlementDate, currency, total, countsByCurrency.get(currency)));
    }

    private void postOneCurrency(UUID runId, LocalDate settlementDate, Currency currency,
                                 long totalMinor, int paymentCount) {

        if (totalMinor <= 0) {
            return;
        }
        if (batchRepository.existsBySettlementDateAndCurrency(settlementDate, currency.getCurrencyCode())) {
            log.info("Settlement for {} {} already posted, skipping", settlementDate, currency);
            return;
        }

        SettlementBatchEntity batch = batchRepository.save(new SettlementBatchEntity(
                runId, settlementDate, currency.getCurrencyCode(), totalMinor, paymentCount));

        ledgerService.openAccount(SettlementAccounts.bank(currency), AccountType.ASSET, currency);

        Money total = new Money(totalMinor, currency);
        UUID transactionId = ledgerService.post(LedgerTransactionRequest
                .source("settlement", batch.getId(), "payout")
                .description("Settlement %s %s (%d payments)".formatted(
                        settlementDate, currency.getCurrencyCode(), paymentCount))
                .debit(SettlementAccounts.bank(currency), total)
                .credit(SettlementAccounts.pspClearing(currency), total)
                .build());

        batch.linkLedgerTransaction(transactionId);

        log.info("Settled {} {} across {} payments (ledger transaction {})",
                total, currency.getCurrencyCode(), paymentCount, transactionId);
    }
}