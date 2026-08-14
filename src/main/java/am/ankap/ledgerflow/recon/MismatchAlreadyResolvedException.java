package am.ankap.ledgerflow.recon;

public class MismatchAlreadyResolvedException extends RuntimeException {

    public MismatchAlreadyResolvedException(String currentStatus) {
        super("Mismatch is already " + currentStatus);
    }
}