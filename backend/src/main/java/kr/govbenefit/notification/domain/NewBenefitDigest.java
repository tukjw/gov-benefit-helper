package kr.govbenefit.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 새 혜택 알림 묶음(REQ-NOTIFY-03, 결정 0046). 사용자에게 보낼 메일 한 통이며 사용자·묶은 날짜당 하나다.
 * 묶음에 든 알림 항목은 {@link NewBenefitTarget#getDigestId()}로 이 묶음을 가리킨다.
 *
 * <p>발송 상태와 시도 기록도 이 행에 둔다(REQ-NOTIFY-02, 결정 0048). 묶은 날 24:00(KST)부터는 보내지 않는다.
 */
@Entity
@Table(name = "new_benefit_digest")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NewBenefitDigest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 계정 ID. 계정이 지워지면 함께 지워진다. */
    private Long userId;

    /** 묶은 날짜(KST) */
    private LocalDate digestDate;

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "varchar(20)")
    private NewBenefitDigestStatus status;

    /** SES에 보낸 횟수 */
    private int attemptCount;

    /** 이 시각부터 보낸다. {@code PENDING}이면 언제나 {@link #deadline()} 전이다. */
    private LocalDateTime nextAttemptAt;

    /** 마지막 발송 실패. 발송기 예외 메시지라 상태와 오류 코드만 담긴다. */
    private String lastError;

    /** SES가 접수한 시각 */
    private LocalDateTime acceptedAt;

    private LocalDateTime createdAt;

    public static NewBenefitDigest pending(Long userId, LocalDate digestDate, LocalDateTime now) {
        NewBenefitDigest digest = new NewBenefitDigest();
        digest.userId = userId;
        digest.digestDate = digestDate;
        digest.status = NewBenefitDigestStatus.PENDING;
        digest.nextAttemptAt = now;
        digest.createdAt = now;
        return digest;
    }

    /** 묶은 날 24:00(KST). 이 시각부터는 보내지 않는다. */
    public LocalDateTime deadline() {
        return digestDate.plusDays(1).atStartOfDay();
    }

    public boolean isExpired(LocalDateTime now) {
        return !now.isBefore(deadline());
    }

    public void accept(LocalDateTime now) {
        attemptCount++;
        status = NewBenefitDigestStatus.ACCEPTED;
        acceptedAt = now;
    }

    public void cancel() {
        status = NewBenefitDigestStatus.CANCELED;
    }

    public void expire() {
        status = NewBenefitDigestStatus.FAILED;
    }

    /**
     * 발송 실패를 기록한다. 다시 보낼 수 있으면 다음 시도 시각을 정하고, 아니면 최종 실패로 끝낸다.
     *
     * @param retryDelays i번째 재시도 전에 기다릴 시간. 개수가 재시도 횟수다.
     * @return 최종 실패로 끝났으면 {@code true}
     */
    public boolean fail(String error, boolean retryable, LocalDateTime now, List<Duration> retryDelays) {
        attemptCount++;
        lastError = error;
        if (retryable && attemptCount <= retryDelays.size()) {
            LocalDateTime next = now.plus(retryDelays.get(attemptCount - 1));
            if (next.isBefore(deadline())) {
                nextAttemptAt = next;
                return false;
            }
        }
        status = NewBenefitDigestStatus.FAILED;
        return true;
    }
}
