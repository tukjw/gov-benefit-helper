package kr.govbenefit.collection.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 수집 실행 하나의 경로별 진행과 처리 건수.
 */
@Entity
@Table(name = "collection_run_step")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CollectionRunStep {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long runId;

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "varchar(30)")
    private SourceEndpoint endpoint;

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "varchar(20)")
    private CollectionStatus status;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private int pageCount;

    private int receivedCount;

    private int createdCount;

    private int changedCount;

    private int unchangedCount;

    private int quarantinedCount;

    private int unlinkedCount;

    public static CollectionRunStep start(Long runId, SourceEndpoint endpoint, LocalDateTime now) {
        CollectionRunStep step = new CollectionRunStep();
        step.runId = runId;
        step.endpoint = endpoint;
        step.status = CollectionStatus.RUNNING;
        step.startedAt = now;
        return step;
    }

    public void addPage(PageResult result) {
        pageCount++;
        receivedCount += result.received();
        createdCount += result.created();
        changedCount += result.changed();
        unchangedCount += result.unchanged();
        quarantinedCount += result.quarantined();
        unlinkedCount += result.unlinked();
    }

    public void succeed(LocalDateTime now) {
        this.status = CollectionStatus.SUCCEEDED;
        this.finishedAt = now;
    }

    public void fail(LocalDateTime now) {
        this.status = CollectionStatus.FAILED;
        this.finishedAt = now;
    }
}
