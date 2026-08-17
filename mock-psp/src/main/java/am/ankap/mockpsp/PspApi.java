package am.ankap.mockpsp;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;

public final class PspApi {

    private PspApi() {
    }

    public record AuthorizeRequest(
            @NotBlank String reference,
            @NotNull @Positive Long amountMinor,
            @NotBlank String currency) {
    }

    public record CaptureRequest(@NotNull @Positive Long amountMinor) {
    }

    public record AuthorizationResponse(
            String id,
            String reference,
            String status,
            long amountMinor,
            long capturedMinor,
            String currency,
            String declineReason,
            Instant createdAt) {
    }

    public record ErrorResponse(String code, String message) {
    }
}