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
@Table(name = "collection_run")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CollectionRun {

    private static final int ERROR_MESSAGE_MAX_LENGTH = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "varchar(20)")
    private CollectionStatus status;

    private String errorMessage;

    public static CollectionRun start(LocalDateTime now) {
        CollectionRun run = new CollectionRun();
        run.startedAt = now;
        run.status = CollectionStatus.RUNNING;
        return run;
    }

    public void succeed(LocalDateTime now) {
        this.status = CollectionStatus.SUCCEEDED;
        this.finishedAt = now;
    }

    public void fail(LocalDateTime now, String errorMessage) {
        this.status = CollectionStatus.FAILED;
        this.finishedAt = now;
        this.errorMessage = truncate(errorMessage);
    }

    private static String truncate(String message) {
        if (message == null || message.length() <= ERROR_MESSAGE_MAX_LENGTH) {
            return message;
        }
        return message.substring(0, ERROR_MESSAGE_MAX_LENGTH);
    }
}
