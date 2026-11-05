package kr.govbenefit.notification.repository;

import kr.govbenefit.notification.domain.NewBenefitTarget;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NewBenefitTargetRepository extends JpaRepository<NewBenefitTarget, NewBenefitTarget.Key> {

    /**
     * 이미 있으면 같은 혜택을 두 번 고른 것이므로 PK 위반으로 실패한다. 그래서 {@code INSERT IGNORE}를 쓰지 않는다.
     */
    @Modifying
    @Query(value = "INSERT INTO new_benefit_target (user_id, benefit_id) VALUES (:userId, :benefitId)",
            nativeQuery = true)
    int insert(@Param("userId") Long userId, @Param("benefitId") Long benefitId);
}
