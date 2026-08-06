package am.ankap.ledgerflow.payment.internal;

import am.ankap.ledgerflow.payment.CreatePaymentCommand;
import am.ankap.ledgerflow.payment.PaymentService;
import am.ankap.ledgerflow.payment.PaymentView;
import am.ankap.ledgerflow.shared.Money;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

import java.util.Currency;
import java.util.UUID;

@RestController
@RequestMapping("/v1/payments")
class PaymentController {

    private final PaymentService paymentService;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    PaymentController(PaymentService paymentService,
                      IdempotencyService idempotencyService,
                      ObjectMapper objectMapper) {
        this.paymentService = paymentService;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    ResponseEntity<?> create(@RequestHeader("X-Merchant-Id") UUID merchantId,
                             @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
                             @Valid @RequestBody CreatePaymentRequest request) {

        String requestHash = RequestHash.of(merchantId, request);
        ClaimResult claim = idempotencyService.claim(merchantId, idempotencyKey, requestHash);

        return switch (claim.outcome()) {
            case REPLAY -> ResponseEntity.status(claim.responseStatus())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(claim.responseBody());
            case CONFLICT -> throw new IdempotencyKeyConflictException(idempotencyKey);
            case IN_PROGRESS -> throw new IdempotencyKeyInUseException(idempotencyKey);
            case OWNED -> createAndRemember(merchantId, idempotencyKey, request);
        };
    }

    @PostMapping("/{paymentId}/authorize")
    PaymentResponse authorize(@PathVariable UUID paymentId) {
        return PaymentResponse.from(paymentService.authorize(paymentId));
    }

    @PostMapping("/{paymentId}/capture")
    PaymentResponse capture(@PathVariable UUID paymentId) {
        return PaymentResponse.from(paymentService.capture(paymentId));
    }

    @GetMapping("/{paymentId}")
    PaymentResponse get(@PathVariable UUID paymentId) {
        return PaymentResponse.from(paymentService.findById(paymentId));
    }

    private ResponseEntity<?> createAndRemember(UUID merchantId, String idempotencyKey,
                                                CreatePaymentRequest request) {
        Money amount = Money.of(request.amountMinor(), Currency.getInstance(request.currency()));
        PaymentView view = paymentService.create(
                new CreatePaymentCommand(merchantId, request.merchantRef(), amount));

        PaymentResponse response = PaymentResponse.from(view);
        idempotencyService.complete(merchantId, idempotencyKey,
                HttpStatus.CREATED.value(), serialize(response), view.id());

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    private String serialize(PaymentResponse response) {
        return objectMapper.writeValueAsString(response);
    }
}