package kr.govbenefit.collection.repository;

import kr.govbenefit.collection.domain.CollectionRun;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CollectionRunRepository extends JpaRepository<CollectionRun, Long> {
}
