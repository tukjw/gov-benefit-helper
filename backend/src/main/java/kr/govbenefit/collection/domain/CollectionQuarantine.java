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

@Entity
@Table(name = "collection_quarantine")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CollectionQuarantine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long runId;

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "varchar(30)")
    private SourceEndpoint endpoint;

    private int page;

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "varchar(50)")
    private QuarantineReason reason;

    @Column(columnDefinition = "json")
    private String payload;

    private LocalDateTime createdAt;

    public static CollectionQuarantine of(Long runId, SourceEndpoint endpoint, int page, QuarantineReason reason,
                                          String payload, LocalDateTime now) {
        CollectionQuarantine quarantine = new CollectionQuarantine();
        quarantine.runId = runId;
        quarantine.endpoint = endpoint;
        quarantine.page = page;
        quarantine.reason = reason;
        quarantine.payload = payload;
        quarantine.createdAt = now;
        return quarantine;
    }
}
