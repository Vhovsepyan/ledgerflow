package am.ankap.ledgerflow.recon.internal;

import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "recon_run")
class ReconRunEntity {

    @Id
    private UUID id;

    @Column(name = "settlement_date", nullable = false, updatable = false)
    private LocalDate settlementDate;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "lines_read", nullable = false)
    private int linesRead;

    @Column(name = "matched", nullable = false)
    private int matched;

    @Column(name = "mismatched", nullable = false)
    private int mismatched;

    @Column(name = "pending_timing", nullable = false)
    private int pendingTiming;

    @Column(name = "started_at", insertable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "error", length = 500)
    private String error;

    protected ReconRunEntity() {
    }

    ReconRunEntity(LocalDate settlementDate) {
        this.id = UUID.randomUUID();
        this.settlementDate = settlementDate;
        this.status = "RUNNING";
    }

    void complete(int linesRead, int matched, int mismatched, int pendingTiming) {
        this.status = "COMPLETED";
        this.linesRead = linesRead;
        this.matched = matched;
        this.mismatched = mismatched;
        this.pendingTiming = pendingTiming;
        this.finishedAt = Instant.now();
    }

    void fail(String error) {
        this.status = "FAILED";
        this.error = error == null ? null : error.substring(0, Math.min(error.length(), 500));
        this.finishedAt = Instant.now();
    }

    UUID getId() {
        return id;
    }
}