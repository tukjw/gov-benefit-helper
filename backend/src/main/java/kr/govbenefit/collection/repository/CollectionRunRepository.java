package kr.govbenefit.collection.repository;

import java.util.Collection;
import java.util.Optional;
import kr.govbenefit.collection.domain.CollectionRun;
import kr.govbenefit.collection.domain.CollectionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CollectionRunRepository extends JpaRepository<CollectionRun, Long> {

    /** 주어진 상태로 끝난 실행 중 가장 늦게 끝난 실행. */
    Optional<CollectionRun> findFirstByStatusInOrderByFinishedAtDesc(Collection<CollectionStatus> statuses);
}
