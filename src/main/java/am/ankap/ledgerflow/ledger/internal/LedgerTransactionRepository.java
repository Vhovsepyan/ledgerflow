package am.ankap.ledgerflow.ledger.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

interface LedgerTransactionRepository extends JpaRepository<LedgerTransactionEntity, UUID> {

    Optional<LedgerTransactionEntity> findByReference(String reference);
}