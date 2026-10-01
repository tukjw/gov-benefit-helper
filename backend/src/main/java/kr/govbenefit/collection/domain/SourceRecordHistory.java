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
 * 내용이 바뀌어 교체된 이전 원문.
 */
@Entity
@Table(name = "source_record_history")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SourceRecordHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String serviceId;

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "varchar(30)")
    private SourceEndpoint endpoint;

    @Column(columnDefinition = "json")
    private String payload;

    @Column(columnDefinition = "char(64)")
    private String contentHash;

    /** 교체된 원문을 마지막으로 받은 시각. */
    private LocalDateTime collectedAt;

    private LocalDateTime replacedAt;

    private Long replacedByRunId;

    static SourceRecordHistory of(SourceRecord replaced, Long runId, LocalDateTime now) {
        SourceRecordHistory history = new SourceRecordHistory();
        history.serviceId = replaced.getServiceId();
        history.endpoint = replaced.getEndpoint();
        history.payload = replaced.getPayload();
        history.contentHash = replaced.getContentHash();
        history.collectedAt = replaced.getCollectedAt();
        history.replacedAt = now;
        history.replacedByRunId = runId;
        return history;
    }
}
