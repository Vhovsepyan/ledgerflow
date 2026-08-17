package am.ankap.mockpsp;

import java.time.Instant;

class Authorization {

    private final String id;
    private final String reference;
    private final long amountMinor;
    private final String currency;
    private final Instant createdAt = Instant.now();

    private volatile String status;
    private volatile long capturedMinor;
    private volatile String declineReason;

    Authorization(String id, String reference, long amountMinor, String currency, String status) {
        this.id = id;
        this.reference = reference;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.status = status;
    }

    void decline(String reason) {
        this.status = "DECLINED";
        this.declineReason = reason;
    }

    void capture(long amount) {
        this.capturedMinor = amount;
        this.status = "CAPTURED";
    }

    public String getReference() {
        return reference;
    }

    public long getCapturedMinor() {
        return capturedMinor;
    }

    public String getCurrency() {
        return currency;
    }

    boolean isAuthorized() {
        return "AUTHORIZED".equals(status);
    }

    boolean isCaptured() {
        return "CAPTURED".equals(status);
    }

    String id() {
        return id;
    }

    PspApi.AuthorizationResponse toResponse() {
        return new PspApi.AuthorizationResponse(
                id, reference, status, amountMinor, capturedMinor, currency, declineReason, createdAt);
    }
}