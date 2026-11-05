package kr.govbenefit.match.service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import kr.govbenefit.benefit.domain.Benefit;
import kr.govbenefit.benefit.dto.BenefitSummary;
import kr.govbenefit.benefit.repository.BenefitRepository;
import kr.govbenefit.benefit.service.BenefitQueryService;
import kr.govbenefit.collection.domain.SourceEndpoint;
import kr.govbenefit.collection.domain.SourceRecord;
import kr.govbenefit.collection.repository.SourceRecordRepository;
import kr.govbenefit.match.domain.SupportConditions;
import kr.govbenefit.region.domain.Regions;
import kr.govbenefit.region.service.RegionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * 조건 비교의 혜택 쪽 판정 입력을 DB에서 만든다. {@link MatchCandidateStore}와 새 혜택 알림 대상 선정이 부른다.
 */
@Component
@RequiredArgsConstructor
public class MatchCandidateLoader {

    private final BenefitRepository benefitRepository;
    private final SourceRecordRepository sourceRecordRepository;
    private final RegionService regionService;
    private final JsonMapper jsonMapper;

    /**
     * 혜택 전체를 검색과 같은 순서(DB 정렬)로 돌려준다. DB 콜레이션은 Java 문자열 비교와 순서가 달라 다시 정렬하지 않는다.
     * 혜택·지원조건·지역을 한 읽기 트랜잭션에서 같은 스냅샷으로 읽는다.
     */
    @Transactional(readOnly = true)
    public List<MatchCandidate> load() {
        Regions regions = regionService.regions();
        Map<String, SupportConditions> conditions = sourceRecordRepository
                .findAllByEndpoint(SourceEndpoint.SUPPORT_CONDITIONS).stream()
                .collect(Collectors.toMap(SourceRecord::getServiceId, this::parse));
        return benefitRepository.findAll(BenefitQueryService.SEARCH_ORDER).stream()
                .map(benefit -> candidate(benefit, regions, conditions.get(benefit.getServiceId())))
                .toList();
    }

    /**
     * 혜택 하나의 판정 입력. 목록과 같은 값을 담는다.
     *
     * @throws IllegalStateException 혜택이 없다. 새 혜택은 혜택 행을 가리키므로 생기지 않는다.
     */
    @Transactional(readOnly = true)
    public MatchCandidate load(Long benefitId) {
        Benefit benefit = benefitRepository.findById(benefitId)
                .orElseThrow(() -> new IllegalStateException("혜택이 없습니다. benefitId=" + benefitId));
        SupportConditions conditions = sourceRecordRepository
                .findByServiceIdAndEndpoint(benefit.getServiceId(), SourceEndpoint.SUPPORT_CONDITIONS)
                .map(this::parse)
                .orElse(null);
        return candidate(benefit, regionService.regions(), conditions);
    }

    private static MatchCandidate candidate(Benefit benefit, Regions regions, SupportConditions conditions) {
        return new MatchCandidate(BenefitSummary.from(benefit), benefit.getUserType(),
                regions.agencyRegionOf(benefit.getAgencyType(), benefit.getAgencyCode()), conditions);
    }

    private SupportConditions parse(SourceRecord record) {
        return SupportConditions.parse(jsonMapper.readTree(record.getPayload()));
    }
}
