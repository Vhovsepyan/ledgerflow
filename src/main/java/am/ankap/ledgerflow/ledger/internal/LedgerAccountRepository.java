package am.ankap.ledgerflow.ledger.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

interface LedgerAccountRepository extends JpaRepository<LedgerAccountEntity, UUID> {

    Optional<LedgerAccountEntity> findByAccountKey(String accountKey);
}

interface LedgerTransactionRepository extends JpaRepository<LedgerTransactionEntity, UUID> {

    Optional<LedgerTransactionEntity> findByReference(String reference);
}

interface LedgerEntryRepository extends JpaRepository<LedgerEntryEntity, UUID> {

    @Query("""
           select coalesce(sum(e.amountMinor), 0)
             from LedgerEntryEntity e
            where e.accountId = :accountId
           """)
    long sumAmountMinorByAccountId(@Param("accountId") UUID accountId);
}