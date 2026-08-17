package am.ankap.ledgerflow.ledger.internal;

import am.ankap.ledgerflow.ledger.CapturedAmount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

interface LedgerEntryRepository extends JpaRepository<LedgerEntryEntity, UUID> {

    @Query("""
           select coalesce(sum(e.amountMinor), 0)
             from LedgerEntryEntity e
            where e.accountId = :accountId
           """)
    long sumAmountMinorByAccountId(@Param("accountId") UUID accountId);

    @Query("""
           select new am.ankap.ledgerflow.ledger.CapturedAmount(
                  t.sourceId, sum(e.amountMinor), e.currency)
             from LedgerTransactionEntity t, LedgerEntryEntity e, LedgerAccountEntity a
            where e.transactionId = t.id
              and a.id = e.accountId
              and t.sourceType = :sourceType
              and t.sourceOperation = 'capture'
              and a.accountKey like 'PSP_CLEARING:%'
            group by t.sourceId, e.currency
           """)
    List<CapturedAmount> findCapturedAmounts(@Param("sourceType") String sourceType);
}