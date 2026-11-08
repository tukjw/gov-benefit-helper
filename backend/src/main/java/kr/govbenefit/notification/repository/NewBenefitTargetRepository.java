package kr.govbenefit.notification.repository;

import java.util.List;
import kr.govbenefit.benefit.domain.Benefit;
import kr.govbenefit.notification.domain.NewBenefitTarget;
import org.springframework.data.domain.Limit;
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

    /** 새 혜택 알림을 켰고 묶지 않은 항목이 있는 계정 ID를 {@code afterId} 다음부터 ID 순으로 읽는다. */
    @Query("select distinct t.userId from NewBenefitTarget t join UserAccount a on a.id = t.userId"
            + " where a.newBenefitNotify = true and t.digestId is null and t.userId > :afterId order by t.userId")
    List<Long> findUnbundledNotifyUserIds(@Param("afterId") Long afterId, Limit limit);

    /** 묶음에 든 혜택을 혜택 ID 순으로 읽는다. */
    @Query("select b from NewBenefitTarget t join Benefit b on b.id = t.benefitId where t.digestId = :digestId"
            + " order by b.id")
    List<Benefit> findDigestBenefits(@Param("digestId") Long digestId);

    /** 사용자의 묶지 않은 항목을 모두 묶음에 넣는다. */
    @Modifying
    @Query(value = "UPDATE new_benefit_target SET digest_id = :digestId WHERE user_id = :userId AND digest_id IS NULL",
            nativeQuery = true)
    int assignDigest(@Param("userId") Long userId, @Param("digestId") Long digestId);

    /** 새 혜택 알림을 끈 사용자의 묶지 않은 항목을 지운다. 다시 켜도 보내지 않는다. */
    @Modifying
    @Query(value = "DELETE t FROM new_benefit_target t JOIN user_account a ON a.id = t.user_id"
            + " WHERE a.new_benefit_notify = FALSE AND t.digest_id IS NULL",
            nativeQuery = true)
    int deleteUnbundledOfNotifyOff();
}
