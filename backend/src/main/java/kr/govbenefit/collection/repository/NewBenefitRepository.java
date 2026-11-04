package kr.govbenefit.collection.repository;

import java.util.List;
import kr.govbenefit.collection.domain.NewBenefit;
import kr.govbenefit.collection.domain.NewBenefitStatus;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NewBenefitRepository extends JpaRepository<NewBenefit, Long> {

    List<NewBenefit> findAllByBenefitIdInAndStatus(List<Long> benefitIds, NewBenefitStatus status);

    /** 알림 대상을 아직 고르지 않은 새 혜택(#117) */
    List<NewBenefit> findAllByStatusAndTargetsSelectedAtIsNullOrderByBenefitId(NewBenefitStatus status);
}
