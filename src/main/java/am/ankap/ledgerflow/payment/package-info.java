@org.springframework.modulith.ApplicationModule(
        displayName = "Payment",
        allowedDependencies = { "shared", "ledger" }
)
package am.ankap.ledgerflow.payment;