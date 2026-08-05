package am.ankap.ledgerflow.payment.internal;

import am.ankap.ledgerflow.payment.IllegalStateTransitionException;
import am.ankap.ledgerflow.payment.PaymentStatus;
import am.ankap.ledgerflow.shared.Money;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

@Entity
@Table(name = "payment")
class PaymentEntity {

    @Id
    private UUID id;

    @Column(name = "merchant_id", nullable = false, updatable = false)
    private UUID merchantId;

    @Column(name = "merchant_ref", updatable = false, length = 255)
    private String merchantRef;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @Column(name = "fee_minor", nullable = false, updatable = false)
    private long feeMinor;

    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private Currency currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private PaymentStatus status;

    @Column(name = "failure_reason", length = 255)
    private String failureReason;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected PaymentEntity() {
    }

    PaymentEntity(UUID id, UUID merchantId, String merchantRef, Money amount, Money fee) {
        this.id = id;
        this.merchantId = merchantId;
        this.merchantRef = merchantRef;
        this.amountMinor = amount.minorUnits();
        this.feeMinor = fee.minorUnits();
        this.currency = amount.currency();
        this.status = PaymentStatus.CREATED;
        this.updatedAt = Instant.now();
    }

    void transitionTo(PaymentStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalStateTransitionException(id, status, target);
        }
        this.status = target;
        this.updatedAt = Instant.now();
    }

    void fail(String reason) {
        transitionTo(PaymentStatus.FAILED);
        this.failureReason = reason;
    }

    UUID getId() {
        return id;
    }

    UUID getMerchantId() {
        return merchantId;
    }

    PaymentStatus getStatus() {
        return status;
    }

    Money getAmount() {
        return new Money(amountMinor, currency);
    }

    Money getFee() {
        return new Money(feeMinor, currency);
    }

    Money getMerchantNet() {
        return getAmount().minus(getFee());
    }
}