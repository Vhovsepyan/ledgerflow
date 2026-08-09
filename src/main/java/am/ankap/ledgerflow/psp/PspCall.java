package am.ankap.ledgerflow.psp;

/**
 * A completed provider interaction.
 *
 * @param attempts how many requests were actually sent (0 if the circuit breaker refused)
 * @param latencyMs total elapsed time including backoff waits
 */
public record PspCall(PspResult result, int attempts, long latencyMs) {
}