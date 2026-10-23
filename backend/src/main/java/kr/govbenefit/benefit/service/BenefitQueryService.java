package kr.govbenefit.benefit.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import kr.govbenefit.benefit.domain.Benefit;
import kr.govbenefit.benefit.domain.DetailStatus;
import kr.govbenefit.benefit.dto.BenefitDetailResponse;
import kr.govbenefit.benefit.dto.BenefitSearchCondition;
import kr.govbenefit.benefit.dto.BenefitSearchResponse;
import kr.govbenefit.benefit.dto.BenefitSummary;
import kr.govbenefit.benefit.dto.BenefitView;
import kr.govbenefit.benefit.dto.Freshness;
import kr.govbenefit.benefit.repository.BenefitDetailRepository;
import kr.govbenefit.benefit.repository.BenefitRepository;
import kr.govbenefit.benefit.repository.BenefitSpecifications;
import kr.govbenefit.collection.domain.CollectionRun;
import kr.govbenefit.collection.domain.CollectionStatus;
import kr.govbenefit.collection.repository.CollectionRunRepository;
import kr.govbenefit.common.api.BusinessException;
import kr.govbenefit.common.api.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BenefitQueryService {

    /**
     * 이름순, 동률은 서비스 ID 순(DEC-SEARCH). 서비스 ID가 유일하므로 순서가 하나로 정해진다.
     * 조건 비교 목록도 같은 결과 안에서 이 순서를 쓴다(DEC-MATCH).
     */
    public static final Sort SEARCH_ORDER = Sort.by(Sort.Order.asc("name"), Sort.Order.asc("serviceId"));

    /** 목록을 갱신한 실행의 상태(결정 0007). FAILED는 갱신이 없는 실행이다. */
    private static final List<CollectionStatus> LIST_UPDATED = List.of(CollectionStatus.SUCCEEDED,
            CollectionStatus.PARTIAL);

    private final BenefitRepository benefitRepository;
    private final BenefitDetailRepository benefitDetailRepository;
    private final CollectionRunRepository collectionRunRepository;
    private final Clock clock;

    public Optional<BenefitView> findByServiceId(String serviceId) {
        return benefitRepository.findByServiceId(serviceId)
                .map(benefit -> new BenefitView(benefit.getServiceId(), benefit.getName(),
                        benefitDetailRepository.existsById(benefit.getId())
                                ? DetailStatus.COLLECTED : DetailStatus.NOT_COLLECTED));
    }

    /**
     * 조건에 맞는 혜택의 한 쪽과 전체 건수. 목록과 건수는 한 읽기 트랜잭션에서 같은 스냅샷을 본다.
     *
     * @param page 1부터 센 쪽 번호
     */
    public BenefitSearchResponse search(BenefitSearchCondition condition, int page, int size) {
        Specification<Benefit> spec = Specification.allOf(
                BenefitSpecifications.nameContainsAll(condition.keywords()),
                BenefitSpecifications.serviceFieldIn(condition.fields()),
                BenefitSpecifications.agencyTypeIn(condition.agencyTypes()));
        if ((long) (page - 1) * size > Integer.MAX_VALUE) {
            // JPA의 시작 위치는 int라 넘길 수 없다. 건수도 int 범위이므로 이 쪽은 항상 마지막 쪽을 넘는다.
            return new BenefitSearchResponse(benefitRepository.count(spec), page, size, List.of());
        }
        Page<Benefit> result = benefitRepository.findAll(spec, PageRequest.of(page - 1, size, SEARCH_ORDER));
        return new BenefitSearchResponse(result.getTotalElements(), page, size,
                result.getContent().stream().map(BenefitSummary::from).toList());
    }

    /**
     * 저장된 혜택 상세와 오래된 정보 여부. 혜택·상세·기준 실행을 한 읽기 트랜잭션에서 같은 스냅샷으로 읽는다.
     *
     * @throws BusinessException 서비스 ID에 해당하는 혜택이 없다({@code NOT_FOUND}).
     */
    public BenefitDetailResponse getDetail(String serviceId) {
        Benefit benefit = benefitRepository.findByServiceId(serviceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "혜택이 없습니다. serviceId=" + serviceId));
        LocalDateTime referenceAt = collectionRunRepository.findFirstByStatusInOrderByFinishedAtDesc(LIST_UPDATED)
                .map(CollectionRun::getFinishedAt)
                .orElse(null);
        return BenefitDetailResponse.of(benefit, benefitDetailRepository.findById(benefit.getId()).orElse(null),
                Freshness.judge(referenceAt, LocalDateTime.now(clock)));
    }
}
