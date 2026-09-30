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
 * 서비스ID·경로별 최신 원문 1벌.
 */
@Entity
@Table(name = "source_record")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SourceRecord {

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

    /** 이 원문을 마지막으로 받은 시각. 내용이 같아도 수집마다 갱신한다. */
    private LocalDateTime collectedAt;

    /** 내용(조회수 제외)이 마지막으로 바뀐 시각. */
    private LocalDateTime contentChangedAt;

    private LocalDateTime createdAt;

    public static SourceRecord create(String serviceId, SourceEndpoint endpoint, String payload, String contentHash,
                                      LocalDateTime now) {
        SourceRecord record = new SourceRecord();
        record.serviceId = serviceId;
        record.endpoint = endpoint;
        record.payload = payload;
        record.contentHash = contentHash;
        record.collectedAt = now;
        record.contentChangedAt = now;
        record.createdAt = now;
        return record;
    }

    public boolean hasSameContent(String contentHash) {
        return this.contentHash.equals(contentHash);
    }

    /**
     * 내용이 바뀐 원문으로 교체한다. 교체 전 원문은 반환한 이력으로 남긴다.
     */
    public SourceRecordHistory replaceContent(String payload, String contentHash, Long runId, LocalDateTime now) {
        SourceRecordHistory history = SourceRecordHistory.of(this, runId, now);
        this.payload = payload;
        this.contentHash = contentHash;
        this.collectedAt = now;
        this.contentChangedAt = now;
        return history;
    }

    /**
     * 내용이 같은 원문을 다시 받았다. 조회수처럼 내용 비교에서 뺀 값이 바뀌었으면 원문만 새 값으로 둔다.
     */
    public void confirm(String payload, boolean payloadChanged, LocalDateTime now) {
        if (payloadChanged) {
            this.payload = payload;
        }
        this.collectedAt = now;
    }
}
