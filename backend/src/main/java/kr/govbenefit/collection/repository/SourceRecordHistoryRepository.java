package kr.govbenefit.collection.repository;

import java.util.List;
import kr.govbenefit.collection.domain.SourceEndpoint;
import kr.govbenefit.collection.domain.SourceRecordHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SourceRecordHistoryRepository extends JpaRepository<SourceRecordHistory, Long> {

    List<SourceRecordHistory> findAllByServiceIdAndEndpointOrderById(String serviceId, SourceEndpoint endpoint);
}
