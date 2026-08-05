package am.ankap.ledgerflow.ledger;

public class ConflictingTransactionException extends RuntimeException {

    public ConflictingTransactionException(String reference) {
        super("Reference '%s' was already posted with different entries".formatted(reference));
    }
}