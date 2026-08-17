package am.ankap.mockpsp;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/admin/chaos")
class ChaosController {

    private final ChaosSettings chaos;

    ChaosController(ChaosSettings chaos) {
        this.chaos = chaos;
    }

    @GetMapping
    Map<String, Object> current() {
        return Map.of(
                "declineRate", chaos.declineRate(),
                "errorRate", chaos.errorRate(),
                "timeoutRate", chaos.timeoutRate(),
                "baseLatencyMs", chaos.baseLatencyMs(),
                "timeoutDelayMs", chaos.timeoutDelayMs());
    }

    @PostMapping
    Map<String, Object> update(@RequestBody ChaosUpdate update) {
        chaos.update(update.declineRate(), update.errorRate(), update.timeoutRate(),
                update.baseLatencyMs(), update.timeoutDelayMs());
        return current();
    }

    record ChaosUpdate(Double declineRate, Double errorRate, Double timeoutRate,
                       Long baseLatencyMs, Long timeoutDelayMs) {
    }
}