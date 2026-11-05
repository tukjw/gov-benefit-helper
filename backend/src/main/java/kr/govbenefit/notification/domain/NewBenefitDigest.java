package kr.govbenefit.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 새 혜택 알림 묶음(REQ-NOTIFY-03, 결정 0046). 사용자에게 보낼 메일 한 통이며 사용자·묶은 날짜당 하나다.
 * 묶음에 든 알림 항목은 {@link NewBenefitTarget#getDigestId()}로 이 묶음을 가리킨다.
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

    private LocalDateTime createdAt;

    public static NewBenefitDigest pending(Long userId, LocalDate digestDate, LocalDateTime now) {
        NewBenefitDigest digest = new NewBenefitDigest();
        digest.userId = userId;
        digest.digestDate = digestDate;
        digest.status = NewBenefitDigestStatus.PENDING;
        digest.createdAt = now;
        return digest;
    }
}
