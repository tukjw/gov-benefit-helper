package kr.govbenefit.collection.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import kr.govbenefit.collection.domain.SourceEndpoint;
import kr.govbenefit.collection.domain.SourceRecord;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SourceRecordRepository extends JpaRepository<SourceRecord, Long> {

    List<SourceRecord> findAllByEndpointAndServiceIdIn(SourceEndpoint endpoint, Collection<String> serviceIds);

    Optional<SourceRecord> findByServiceIdAndEndpoint(String serviceId, SourceEndpoint endpoint);
}
