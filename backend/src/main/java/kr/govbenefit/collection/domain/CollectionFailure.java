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
 * 수집 실행에서 받거나 저장하지 못한 쪽. 메시지에는 인증키·요청 헤더를 담지 않는다.
 */
@Entity
@Table(name = "collection_failure")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CollectionFailure {

    private static final int MESSAGE_MAX_LENGTH = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long runId;

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "varchar(30)")
    private SourceEndpoint endpoint;

    private int page;

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "varchar(30)")
    private CollectionErrorType errorType;

    private int attempts;

    private String message;

    private LocalDateTime createdAt;

    public static CollectionFailure of(Long runId, SourceEndpoint endpoint, int page, CollectionErrorType errorType,
                                       int attempts, String message, LocalDateTime now) {
        CollectionFailure failure = new CollectionFailure();
        failure.runId = runId;
        failure.endpoint = endpoint;
        failure.page = page;
        failure.errorType = errorType;
        failure.attempts = attempts;
        failure.message = message == null || message.length() <= MESSAGE_MAX_LENGTH
                ? message : message.substring(0, MESSAGE_MAX_LENGTH);
        failure.createdAt = now;
        return failure;
    }
}
