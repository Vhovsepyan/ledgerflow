package am.ankap.ledgerflow.ledger.internal;

import am.ankap.ledgerflow.ledger.*;
import am.ankap.ledgerflow.shared.Money;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Currency;
import java.util.Optional;
import java.util.UUID;

@Service
class DefaultLedgerService implements LedgerService {

    private final LedgerAccountRepository accountRepository;
    private final LedgerTransactionRepository transactionRepository;
    private final LedgerEntryRepository entryRepository;

    DefaultLedgerService(LedgerAccountRepository accountRepository,
                         LedgerTransactionRepository transactionRepository,
                         LedgerEntryRepository entryRepository) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.entryRepository = entryRepository;
    }

    @Override
    @Transactional
    public void openAccount(String accountKey, AccountType accountType, Currency currency) {
        accountRepository.findByAccountKey(accountKey)
                .orElseGet(() -> accountRepository.save(
                        new LedgerAccountEntity(UUID.randomUUID(), accountKey, accountType, currency)));
    }

    @Override
    @Transactional
    public UUID post(LedgerTransactionRequest request) {
        String entriesHash = EntriesHash.of(request.getEntries());

        Optional<LedgerTransactionEntity> existing =
                transactionRepository.findByReference(request.getReference());

        if (existing.isPresent()) {
            LedgerTransactionEntity transaction = existing.get();
            if (!transaction.getEntriesHash().equals(entriesHash)) {
                throw new ConflictingTransactionException(request.getReference());
            }
            return transaction.getId();
        }

        UUID transactionId = UUID.randomUUID();
        transactionRepository.save(new LedgerTransactionEntity(
                transactionId, request.getReference(), request.getDescription(), entriesHash));

        for (EntryLine line : request.getEntries()) {
            LedgerAccountEntity account = requireAccount(line.accountKey());
            entryRepository.save(new LedgerEntryEntity(
                    UUID.randomUUID(), transactionId, account.getId(), line.amount()));
        }
        return transactionId;
    }

    @Override
    @Transactional(readOnly = true)
    public Money balanceOf(String accountKey) {
        LedgerAccountEntity account = requireAccount(accountKey);
        long total = entryRepository.sumAmountMinorByAccountId(account.getId());
        return new Money(total, account.getCurrency());
    }

    private LedgerAccountEntity requireAccount(String accountKey) {
        return accountRepository.findByAccountKey(accountKey)
                .orElseThrow(() -> new IllegalArgumentException("Unknown account: " + accountKey));
    }
}