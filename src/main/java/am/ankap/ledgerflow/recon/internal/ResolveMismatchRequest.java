package am.ankap.ledgerflow.recon.internal;

import am.ankap.ledgerflow.recon.MismatchStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

record ResolveMismatchRequest(
        @NotNull MismatchStatus status,
        @NotBlank @Size(max = 255) String resolvedBy,
        @Size(max = 1000) String note) {
}