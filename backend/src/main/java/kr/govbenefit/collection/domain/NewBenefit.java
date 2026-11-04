package kr.govbenefit.collection.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 수집에서 처음 저장된 혜택. 최초 전체 수집과 이 기능 이전부터 있던 혜택은 없다(결정 0042).
 */
@Entity
@Table(name = "new_benefit")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NewBenefit {

    @Id
    private Long benefitId;

    /** 혜택을 처음 저장한 수집 실행. */
    private Long detectedRunId;

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "varchar(20)")
    private NewBenefitStatus status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public static NewBenefit detect(Long benefitId, Long runId, LocalDateTime now) {
        NewBenefit newBenefit = new NewBenefit();
        newBenefit.benefitId = benefitId;
        newBenefit.detectedRunId = runId;
        newBenefit.status = NewBenefitStatus.WAITING_CONDITIONS;
        newBenefit.createdAt = now;
        newBenefit.updatedAt = now;
        return newBenefit;
    }

    public void markReady(LocalDateTime now) {
        this.status = NewBenefitStatus.READY;
        this.updatedAt = now;
    }
}
