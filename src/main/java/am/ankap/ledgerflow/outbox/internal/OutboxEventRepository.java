package am.ankap.ledgerflow.outbox.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

interface OutboxEventRepository extends JpaRepository<OutboxEventEntity, UUID> {

    /**
     * Takes a batch of unpublished events, locking them so other relay instances
     * skip past and pick up different rows. Must run inside a transaction.
     */
    @Query(value = """
                   select * from outbox_event
                    where published_at is null
                    order by sequence_no
                    limit :batchSize
                    for update skip locked
                   """, nativeQuery = true)
    List<OutboxEventEntity> lockUnpublishedBatch(@Param("batchSize") int batchSize);

    long countByPublishedAtIsNull();
}