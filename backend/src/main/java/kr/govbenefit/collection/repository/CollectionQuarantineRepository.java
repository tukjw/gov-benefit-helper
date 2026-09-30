package kr.govbenefit.collection.repository;

import java.util.List;
import kr.govbenefit.collection.domain.CollectionQuarantine;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CollectionQuarantineRepository extends JpaRepository<CollectionQuarantine, Long> {

    List<CollectionQuarantine> findAllByRunId(Long runId);
}
