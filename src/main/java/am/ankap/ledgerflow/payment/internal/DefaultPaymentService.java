package am.ankap.ledgerflow.payment.internal;

import am.ankap.ledgerflow.ledger.AccountType;
import am.ankap.ledgerflow.ledger.LedgerService;
import am.ankap.ledgerflow.ledger.LedgerTransactionRequest;
import am.ankap.ledgerflow.payment.*;
import am.ankap.ledgerflow.shared.Money;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Currency;
import java.util.UUID;

@Service
class DefaultPaymentService implements PaymentService {

    private final PaymentRepository paymentRepository;
    private final LedgerService ledgerService;
    private final FeePolicy feePolicy;

    DefaultPaymentService(PaymentRepository paymentRepository,
                          LedgerService ledgerService,
                          FeePolicy feePolicy) {
        this.paymentRepository = paymentRepository;
        this.ledgerService = ledgerService;
        this.feePolicy = feePolicy;
    }

    @Override
    @Transactional
    public PaymentView create(CreatePaymentCommand command) {
        Money fee = feePolicy.feeFor(command.amount());
        PaymentEntity payment = paymentRepository.save(new PaymentEntity(
                UUID.randomUUID(), command.merchantId(), command.merchantRef(), command.amount(), fee));
        return toView(payment);
    }

    @Override
    @Transactional
    public PaymentView authorize(UUID paymentId) {
        PaymentEntity payment = require(paymentId);
        payment.transitionTo(PaymentStatus.AUTHORIZED);
        return toView(payment);
    }

    @Override
    @Transactional
    public PaymentView capture(UUID paymentId) {
        PaymentEntity payment = require(paymentId);
        payment.transitionTo(PaymentStatus.CAPTURED);

        Currency currency = payment.getAmount().currency();
        openAccountsIfNeeded(payment.getMerchantId(), currency);

        ledgerService.post(LedgerTransactionRequest
                .reference("payment:%s:capture".formatted(payment.getId()))
                .description("Capture payment " + payment.getId())
                .debit(LedgerAccounts.pspClearing(currency), payment.getAmount())
                .credit(LedgerAccounts.merchantPayable(payment.getMerchantId(), currency),
                        payment.getMerchantNet())
                .credit(LedgerAccounts.feeRevenue(currency), payment.getFee())
                .build());

        return toView(payment);
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentView findById(UUID paymentId) {
        return toView(require(paymentId));
    }

    private void openAccountsIfNeeded(UUID merchantId, Currency currency) {
        ledgerService.openAccount(LedgerAccounts.pspClearing(currency), AccountType.ASSET, currency);
        ledgerService.openAccount(LedgerAccounts.merchantPayable(merchantId, currency),
                AccountType.LIABILITY, currency);
        ledgerService.openAccount(LedgerAccounts.feeRevenue(currency), AccountType.REVENUE, currency);
    }

    private PaymentEntity require(UUID paymentId) {
        return paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));
    }

    private PaymentView toView(PaymentEntity payment) {
        return new PaymentView(
                payment.getId(), payment.getMerchantId(), payment.getMerchantRef(),
                payment.getStatus(), payment.getAmount(), payment.getFee(),
                payment.getMerchantNet(), payment.getFailureReason(), payment.getCreatedAt());
    }
}