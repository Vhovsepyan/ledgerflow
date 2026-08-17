package am.ankap.mockpsp;

import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@RestController
@RequestMapping("/psp")
class PspController {

    private static final Logger log = LoggerFactory.getLogger(PspController.class);

    private final AuthorizationStore store;
    private final ChaosSettings chaos;

    PspController(AuthorizationStore store, ChaosSettings chaos) {
        this.store = store;
        this.chaos = chaos;
    }

    @PostMapping("/authorizations")
    ResponseEntity<PspApi.AuthorizationResponse> authorize(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody PspApi.AuthorizeRequest request) {

        sleep(chaos.baseLatencyMs());

        // A degraded provider is slow for every call, including idempotent replays.
        maybeSwallowTheResponse("authorize", request.reference());

        var replayed = store.findByIdempotencyKey(idempotencyKey);
        if (replayed.isPresent()) {
            log.info("Replaying authorization for key {}", idempotencyKey);
            return ResponseEntity.ok(replayed.get().toResponse());
        }

        maybeFailWithError();

        String id = "auth_" + UUID.randomUUID();
        Authorization authorization = new Authorization(
                id, request.reference(), request.amountMinor(), request.currency(), "AUTHORIZED");

        if (roll() < chaos.declineRate()) {
            authorization.decline("insufficient_funds");
        }

        store.save(authorization, idempotencyKey);

        HttpStatus status = authorization.isAuthorized() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(authorization.toResponse());
    }

    @PostMapping("/authorizations/{id}/captures")
    ResponseEntity<PspApi.AuthorizationResponse> capture(
            @PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody PspApi.CaptureRequest request) {

        sleep(chaos.baseLatencyMs());

        Authorization authorization = store.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Unknown authorization " + id));

        // Only delay real work — an unknown id should fail fast.
        maybeSwallowTheResponse("capture", id);

        if (authorization.isCaptured()) {
            return ResponseEntity.ok(authorization.toResponse());
        }
        if (!authorization.isAuthorized()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Authorization is not capturable");
        }

        maybeFailWithError();

        authorization.capture(request.amountMinor());
        store.linkIdempotencyKey(idempotencyKey, id);

        return ResponseEntity.ok(authorization.toResponse());
    }

    @GetMapping("/authorizations/{id}")
    PspApi.AuthorizationResponse get(@PathVariable String id) {
        return store.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Unknown authorization " + id))
                .toResponse();
    }

    /** Lookup by the caller's own reference. This is how an unknown outcome gets resolved. */
    @GetMapping("/authorizations")
    PspApi.AuthorizationResponse findByReference(@RequestParam String reference) {
        return store.findByReference(reference)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "No authorization for " + reference))
                .toResponse();
    }

    private void maybeFailWithError() {
        if (roll() < chaos.errorRate()) {
            log.warn("Chaos: returning 500");
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Provider unavailable");
        }
    }

    private void maybeSwallowTheResponse(String operation, String reference) {
        if (roll() < chaos.timeoutRate()) {
            log.warn("Chaos: {} for {} will be delayed by {}ms", operation, reference, chaos.timeoutDelayMs());
            sleep(chaos.timeoutDelayMs());
        }
    }

    private static double roll() {
        return ThreadLocalRandom.current().nextDouble();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}