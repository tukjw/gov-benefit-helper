package kr.govbenefit.collection.repository;

import java.util.List;
import kr.govbenefit.collection.domain.CollectionRunStep;
import kr.govbenefit.collection.domain.CollectionStatus;
import kr.govbenefit.collection.domain.SourceEndpoint;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CollectionRunStepRepository extends JpaRepository<CollectionRunStep, Long> {

    List<CollectionRunStep> findAllByRunIdOrderById(Long runId);

    boolean existsByEndpointAndStatus(SourceEndpoint endpoint, CollectionStatus status);
}
