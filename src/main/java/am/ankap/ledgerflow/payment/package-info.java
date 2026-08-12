@org.springframework.modulith.ApplicationModule(
        displayName = "Payment",
        allowedDependencies = { "shared", "ledger", "psp", "outbox" }
)
package am.ankap.ledgerflow.payment;