package am.ankap.ledgerflow.payment;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentStatusTest {

    @Test
    void allowsTheHappyPath() {
        assertThat(PaymentStatus.CREATED.canTransitionTo(PaymentStatus.AUTHORIZED)).isTrue();
        assertThat(PaymentStatus.AUTHORIZED.canTransitionTo(PaymentStatus.CAPTURED)).isTrue();
        assertThat(PaymentStatus.CAPTURED.canTransitionTo(PaymentStatus.REFUNDED)).isTrue();
    }

    @Test
    void forbidsSkippingAuthorization() {
        assertThat(PaymentStatus.CREATED.canTransitionTo(PaymentStatus.CAPTURED)).isFalse();
    }

    @Test
    void forbidsLeavingATerminalState() {
        assertThat(PaymentStatus.FAILED.isTerminal()).isTrue();
        assertThat(PaymentStatus.CANCELED.isTerminal()).isTrue();
        assertThat(PaymentStatus.REFUNDED.isTerminal()).isTrue();
        assertThat(PaymentStatus.FAILED.canTransitionTo(PaymentStatus.CAPTURED)).isFalse();
    }

    @Test
    void everyStatusHasARule() {
        for (PaymentStatus status : PaymentStatus.values()) {
            assertThat(status.isTerminal() || !status.isTerminal()).isTrue();
        }
    }
}