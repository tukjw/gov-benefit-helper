package kr.govbenefit.notification.repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import kr.govbenefit.notification.domain.NewBenefitDigest;
import kr.govbenefit.notification.domain.NewBenefitDigestStatus;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NewBenefitDigestRepository extends JpaRepository<NewBenefitDigest, Long> {

    boolean existsByUserIdAndDigestDate(Long userId, LocalDate digestDate);

    /** {@code status}이고 다음 시도 시각이 {@code now} 이전인 묶음 ID를 {@code afterId} 다음부터 ID 순으로 읽는다. */
    @Query("select d.id from NewBenefitDigest d where d.status = :status and d.nextAttemptAt <= :now"
            + " and d.id > :afterId order by d.id")
    List<Long> findDueIds(@Param("status") NewBenefitDigestStatus status, @Param("now") LocalDateTime now,
                          @Param("afterId") Long afterId, Limit limit);
}
