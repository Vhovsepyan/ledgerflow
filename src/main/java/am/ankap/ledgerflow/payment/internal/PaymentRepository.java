package am.ankap.ledgerflow.payment.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface PaymentRepository extends JpaRepository<PaymentEntity, UUID> {
}