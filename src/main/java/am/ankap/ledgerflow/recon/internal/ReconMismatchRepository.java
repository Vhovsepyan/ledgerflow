package am.ankap.ledgerflow.recon.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface ReconMismatchRepository extends JpaRepository<ReconMismatchEntity, UUID> {

    long countByStatus(String status);
}