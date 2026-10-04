package kr.govbenefit.benefit.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import kr.govbenefit.benefit.domain.Benefit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface BenefitRepository extends JpaRepository<Benefit, Long>, JpaSpecificationExecutor<Benefit> {

    List<Benefit> findAllByServiceIdIn(Collection<String> serviceIds);

    Optional<Benefit> findByServiceId(String serviceId);
}
