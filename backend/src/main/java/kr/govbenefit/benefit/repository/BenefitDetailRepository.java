package kr.govbenefit.benefit.repository;

import java.util.Collection;
import java.util.List;
import kr.govbenefit.benefit.domain.BenefitDetail;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BenefitDetailRepository extends JpaRepository<BenefitDetail, Long> {

    List<BenefitDetail> findAllByBenefitIdIn(Collection<Long> benefitIds);
}
