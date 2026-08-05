package am.ankap.ledgerflow.ledger;

public class UnbalancedTransactionException extends RuntimeException {

    public UnbalancedTransactionException(String currencyCode, long residual) {
        super("Transaction does not balance in %s: residual %d minor units"
                .formatted(currencyCode, residual));
    }
}