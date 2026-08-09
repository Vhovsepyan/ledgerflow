package am.ankap.ledgerflow.payment.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface PspAttemptRepository extends JpaRepository<PspAttemptEntity, UUID> {
}