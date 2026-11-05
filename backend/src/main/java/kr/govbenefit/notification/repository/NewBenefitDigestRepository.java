package kr.govbenefit.notification.repository;

import java.time.LocalDate;
import kr.govbenefit.notification.domain.NewBenefitDigest;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NewBenefitDigestRepository extends JpaRepository<NewBenefitDigest, Long> {

    boolean existsByUserIdAndDigestDate(Long userId, LocalDate digestDate);
}
