@org.springframework.modulith.ApplicationModule(
        displayName = "Payment",
        allowedDependencies = { "shared", "ledger", "psp" }
)
package am.ankap.ledgerflow.payment;