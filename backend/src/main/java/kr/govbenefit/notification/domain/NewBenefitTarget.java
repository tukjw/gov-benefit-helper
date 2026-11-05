package kr.govbenefit.notification.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 새 혜택 알림 대상(REQ-NOTIFY-03). 사용자·새 혜택 조합당 하나다. 쓰기는 저장소의 네이티브 쿼리로만 하므로 생성 메서드가 없다.
 */
@Entity
@Table(name = "new_benefit_target")
@IdClass(NewBenefitTarget.Key.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NewBenefitTarget {

    /** 계정 ID. 계정이 지워지면 함께 지워진다. */
    @Id
    private Long userId;

    /** 새 혜택({@code new_benefit})의 혜택 ID */
    @Id
    private Long benefitId;

    public record Key(Long userId, Long benefitId) implements Serializable {
    }
}
