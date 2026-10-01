package kr.govbenefit.benefit.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import kr.govbenefit.benefit.domain.Benefit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BenefitRepository extends JpaRepository<Benefit, Long> {

    List<Benefit> findAllByServiceIdIn(Collection<String> serviceIds);

    Optional<Benefit> findByServiceId(String serviceId);
}
