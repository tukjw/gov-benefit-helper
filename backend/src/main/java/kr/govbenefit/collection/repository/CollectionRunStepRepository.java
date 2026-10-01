package kr.govbenefit.collection.repository;

import java.util.List;
import kr.govbenefit.collection.domain.CollectionRunStep;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CollectionRunStepRepository extends JpaRepository<CollectionRunStep, Long> {

    List<CollectionRunStep> findAllByRunIdOrderById(Long runId);
}
