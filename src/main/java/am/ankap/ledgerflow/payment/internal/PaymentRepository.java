package am.ankap.ledgerflow.payment.internal;

import am.ankap.ledgerflow.payment.PaymentStatus;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

interface PaymentRepository extends JpaRepository<PaymentEntity, UUID> {

    @Query("""
           select p.id from PaymentEntity p
            where p.status in :statuses
              and p.nextVerificationAt <= :now
            order by p.nextVerificationAt asc
           """)
    List<UUID> findDueForVerification(@Param("statuses") List<PaymentStatus> statuses,
                                      @Param("now") Instant now,
                                      Limit limit);
}