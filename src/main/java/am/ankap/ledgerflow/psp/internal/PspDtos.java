package am.ankap.ledgerflow.psp.internal;

final class PspDtos {

    private PspDtos() {
    }

    record AuthorizeRequest(String reference, long amountMinor, String currency) {
    }

    record CaptureRequest(long amountMinor) {
    }

    record AuthorizationResponse(
            String id,
            String reference,
            String status,
            long amountMinor,
            long capturedMinor,
            String currency,
            String declineReason) {
    }
}