package kr.govbenefit.collection.repository;

import java.util.List;
import kr.govbenefit.collection.domain.NewBenefit;
import kr.govbenefit.collection.domain.NewBenefitStatus;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NewBenefitRepository extends JpaRepository<NewBenefit, Long> {

    List<NewBenefit> findAllByBenefitIdInAndStatus(List<Long> benefitIds, NewBenefitStatus status);
}
