package kr.govbenefit.region.repository;

import java.util.List;
import kr.govbenefit.region.domain.Region;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RegionRepository extends JpaRepository<Region, String> {

    List<Region> findAllByOrderByDisplayOrderAsc();
}
