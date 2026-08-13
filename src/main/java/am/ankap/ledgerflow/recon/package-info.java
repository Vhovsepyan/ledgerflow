@org.springframework.modulith.ApplicationModule(
        displayName = "Reconciliation",
        allowedDependencies = { "shared", "ledger", "payment" }
)
package am.ankap.ledgerflow.recon;