package am.ankap.mockpsp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@RestController
@RequestMapping("/psp/settlements")
class SettlementController {

    private static final Logger log = LoggerFactory.getLogger(SettlementController.class);

    private final AuthorizationStore store;

    private volatile boolean injectErrors = true;

    SettlementController(AuthorizationStore store) {
        this.store = store;
    }

    /**
     * A daily settlement file, as a real provider would publish it.
     *
     * When error injection is on, the file deliberately disagrees with reality:
     * one captured payment is dropped, one amount is altered, and one line
     * refers to a payment the caller never made. Reconciliation must find all three.
     */
    @GetMapping(produces = "text/csv")
    String settlementFile(@RequestParam(required = false) String date) {
        LocalDate settlementDate = date == null ? LocalDate.now() : LocalDate.parse(date);

        List<Authorization> captured = store.allCaptured();
        StringBuilder csv = new StringBuilder("reference,psp_reference,amount_minor,currency,captured_at\n");

        int index = 0;
        for (Authorization authorization : captured) {
            index++;

            if (injectErrors && index == 2) {
                log.warn("Settlement file: dropping {} on purpose", authorization.getReference());
                continue;
            }

            long amount = authorization.getCapturedMinor();
            if (injectErrors && index == 3) {
                amount = amount - 10;
                log.warn("Settlement file: understating {} by 10 on purpose", authorization.getReference());
            }

            csv.append("%s,%s,%d,%s,%s%n".formatted(
                    authorization.getReference(),
                    authorization.id(),
                    amount,
                    authorization.getCurrency(),
                    settlementDate));
        }

        if (injectErrors && !captured.isEmpty()) {
            String ghost = "payment-" + UUID.randomUUID();
            log.warn("Settlement file: adding phantom line {} on purpose", ghost);
            csv.append("%s,auth_%s,%d,%s,%s%n".formatted(
                    ghost, UUID.randomUUID(), 7700L, "USD", settlementDate));
        }

        return csv.toString();
    }

    @PostMapping("/error-injection")
    Map<String, Boolean> setErrorInjection(@RequestBody Map<String, Boolean> body) {
        this.injectErrors = body.getOrDefault("enabled", true);
        return Map.of("enabled", injectErrors);
    }
}