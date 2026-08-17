package am.ankap.mockpsp;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ChaosSettings {

    private volatile double declineRate;
    private volatile double errorRate;
    private volatile double timeoutRate;
    private volatile long baseLatencyMs;
    private volatile long timeoutDelayMs;

    ChaosSettings(@Value("${mockpsp.chaos.decline-rate:0.10}") double declineRate,
                  @Value("${mockpsp.chaos.error-rate:0.10}") double errorRate,
                  @Value("${mockpsp.chaos.timeout-rate:0.10}") double timeoutRate,
                  @Value("${mockpsp.chaos.base-latency-ms:50}") long baseLatencyMs,
                  @Value("${mockpsp.chaos.timeout-delay-ms:8000}") long timeoutDelayMs) {
        this.declineRate = declineRate;
        this.errorRate = errorRate;
        this.timeoutRate = timeoutRate;
        this.baseLatencyMs = baseLatencyMs;
        this.timeoutDelayMs = timeoutDelayMs;
    }

    public double declineRate() {
        return declineRate;
    }

    public double errorRate() {
        return errorRate;
    }

    public double timeoutRate() {
        return timeoutRate;
    }

    public long baseLatencyMs() {
        return baseLatencyMs;
    }

    public long timeoutDelayMs() {
        return timeoutDelayMs;
    }

    public void update(Double decline, Double error, Double timeout, Long latency, Long timeoutDelay) {
        if (decline != null) this.declineRate = decline;
        if (error != null) this.errorRate = error;
        if (timeout != null) this.timeoutRate = timeout;
        if (latency != null) this.baseLatencyMs = latency;
        if (timeoutDelay != null) this.timeoutDelayMs = timeoutDelay;
    }
}