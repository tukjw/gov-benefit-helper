package kr.govbenefit.collection.repository;

import java.util.List;
import kr.govbenefit.collection.domain.CollectionFailure;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CollectionFailureRepository extends JpaRepository<CollectionFailure, Long> {

    List<CollectionFailure> findAllByRunIdOrderById(Long runId);
}
