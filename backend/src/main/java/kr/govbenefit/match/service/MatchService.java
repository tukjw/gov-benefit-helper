package kr.govbenefit.match.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import kr.govbenefit.account.repository.UserAccountRepository;
import kr.govbenefit.benefit.domain.Benefit;
import kr.govbenefit.benefit.repository.BenefitRepository;
import kr.govbenefit.benefit.service.BenefitQueryService;
import kr.govbenefit.collection.domain.SourceEndpoint;
import kr.govbenefit.collection.domain.SourceRecord;
import kr.govbenefit.collection.repository.SourceRecordRepository;
import kr.govbenefit.common.api.BusinessException;
import kr.govbenefit.common.api.ErrorCode;
import kr.govbenefit.common.api.InvalidInputException;
import kr.govbenefit.match.domain.ConditionMatcher;
import kr.govbenefit.match.domain.MatchOutcome;
import kr.govbenefit.match.domain.MatchSubject;
import kr.govbenefit.match.domain.SupportConditions;
import kr.govbenefit.match.dto.MatchListResponse;
import kr.govbenefit.match.dto.MatchListResponse.MatchListItem;
import kr.govbenefit.match.dto.MatchResponse;
import kr.govbenefit.match.dto.RegionScope;
import kr.govbenefit.profile.domain.ProfileValues;
import kr.govbenefit.profile.dto.ProfileRequest;
import kr.govbenefit.profile.repository.UserProfileRepository;
import kr.govbenefit.profile.service.ProfileValidator;
import kr.govbenefit.region.domain.AgencyRegion;
import kr.govbenefit.region.domain.Regions;
import kr.govbenefit.region.service.RegionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * 프로필과 혜택의 지원조건 비교(REQ-MATCH-01, DEC-MATCH). 로그인 사용자는 토큰 주인의 저장된 프로필로(결정 0018), 비회원은
 * 요청에 담은 값으로 비교한다. 요청 값은 저장하지 않으며, 두 경로는 같은 판정·범위·정렬·페이지를 쓴다.
 * 지원조건은 저장된 원문(supportConditions)을 조회할 때마다 읽으므로(결정 0008) 프로필을 바꾸면 다음 비교에 바로 반영된다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MatchService {

    private final UserAccountRepository userAccountRepository;
    private final UserProfileRepository userProfileRepository;
    private final BenefitRepository benefitRepository;
    private final SourceRecordRepository sourceRecordRepository;
    private final RegionService regionService;
    private final ProfileValidator profileValidator;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    /**
     * 프로필·혜택·지원조건을 한 읽기 트랜잭션에서 같은 스냅샷으로 읽는다. 프로필을 저장한 적이 없으면 모든 값이 미입력이다.
     *
     * @throws BusinessException 계정이 없다({@code UNAUTHENTICATED}). 수집되지 않은 서비스 ID다({@code NOT_FOUND}).
     */
    public MatchResponse match(Long userId, String serviceId) {
        requireAccount(userId);
        LocalDate today = LocalDate.now(clock);
        return match(subject(userId, today), serviceId, today);
    }

    /**
     * 요청에 담은 프로필 값으로 비교한다. 값을 검증한 뒤 혜택을 찾으므로 잘못된 값이면 서비스 ID와 관계없이 거절한다.
     *
     * @throws InvalidInputException 지원하지 않는 값이나 모순된 입력이다. 프로필 저장과 같은 항목별 오류다.
     * @throws BusinessException     수집되지 않은 서비스 ID다({@code NOT_FOUND}).
     */
    public MatchResponse matchWith(ProfileRequest profile, String serviceId) {
        ProfileValues values = profileValidator.validate(profile);
        LocalDate today = LocalDate.now(clock);
        return match(MatchSubject.of(values, today), serviceId, today);
    }

    private MatchResponse match(MatchSubject subject, String serviceId, LocalDate today) {
        Benefit benefit = benefitRepository.findByServiceId(serviceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "혜택이 없습니다. serviceId=" + serviceId));
        SupportConditions conditions = sourceRecordRepository
                .findByServiceIdAndEndpoint(serviceId, SourceEndpoint.SUPPORT_CONDITIONS)
                .map(this::parse)
                .orElse(null);
        Regions regions = regionService.regions();
        AgencyRegion agencyRegion = regions.agencyRegionOf(benefit.getAgencyType(), benefit.getAgencyCode());
        return MatchResponse.of(benefit, conditions, subject, agencyRegion, regions,
                ConditionMatcher.match(subject, conditions, benefit.getUserType(), agencyRegion), today);
    }

    /**
     * 비교 결과 순(부합 → 확인 필요) 혜택 한 쪽. 개인 대상이 아닌 혜택(불일치)은 뺀다.
     * 운영 기관 지역 범위는 판정과 같은 운영 기관 지역으로 메모리에서 거른다. 범위 규칙을 SQL에 따로 두지 않는다.
     * 순서가 프로필에 따라 달라 DB에서 자를 수 없으므로 요청마다 전체 혜택을 판정한다. 같은 결과 안에서는 검색과 같은 순서가
     * 되도록 DB가 정렬한 순서를 그대로 두고 결과별로 나눈다. DB 콜레이션은 Java 문자열 비교와 순서가 달라 다시 정렬하지 않는다.
     * 프로필·혜택·지원조건을 한 읽기 트랜잭션에서 같은 스냅샷으로 읽는다.
     *
     * @param requested 요청한 지역 범위. 없으면 null이다.
     * @param page      1부터 센 쪽 번호
     * @throws BusinessException 계정이 없다({@code UNAUTHENTICATED}).
     */
    public MatchListResponse list(Long userId, RegionScope requested, int page, int size) {
        requireAccount(userId);
        LocalDate today = LocalDate.now(clock);
        return list(subject(userId, today), requested, page, size, today);
    }

    /**
     * 요청에 담은 프로필 값으로 비교한 목록. 규칙은 {@link #list(Long, RegionScope, int, int)}와 같다.
     *
     * @throws InvalidInputException 지원하지 않는 값이나 모순된 입력이다. 프로필 저장과 같은 항목별 오류다.
     */
    public MatchListResponse listWith(ProfileRequest profile, RegionScope requested, int page, int size) {
        ProfileValues values = profileValidator.validate(profile);
        LocalDate today = LocalDate.now(clock);
        return list(MatchSubject.of(values, today), requested, page, size, today);
    }

    private MatchListResponse list(MatchSubject subject, RegionScope requested, int page, int size,
                                   LocalDate today) {
        RegionScope scope = scope(requested, subject);
        Regions regions = regionService.regions();
        Map<String, SupportConditions> conditions = sourceRecordRepository
                .findAllByEndpoint(SourceEndpoint.SUPPORT_CONDITIONS).stream()
                .collect(Collectors.toMap(SourceRecord::getServiceId, this::parse));
        // 부합을 담고, 다 판정한 뒤 확인 필요를 이어 붙인다.
        List<MatchListItem> listed = new ArrayList<>();
        List<MatchListItem> needsCheck = new ArrayList<>();
        for (Benefit benefit : benefitRepository.findAll(BenefitQueryService.SEARCH_ORDER)) {
            AgencyRegion agencyRegion = regions.agencyRegionOf(benefit.getAgencyType(), benefit.getAgencyCode());
            if (scope == RegionScope.MY_REGION && !agencyRegion.isWithin(subject.sidoCode(), subject.sigunguCode())) {
                continue;
            }
            // 지원조건을 수집하지 못한 혜택은 null로 판정해 확인 필요가 된다. 상세 비교와 같다.
            MatchOutcome outcome = ConditionMatcher.match(subject, conditions.get(benefit.getServiceId()),
                    benefit.getUserType(), agencyRegion);
            switch (outcome.result()) {
                case MATCH -> listed.add(MatchListItem.of(benefit, outcome));
                case NEEDS_CHECK -> needsCheck.add(MatchListItem.of(benefit, outcome));
                case MISMATCH -> {
                }
            }
        }
        listed.addAll(needsCheck);
        long from = (long) (page - 1) * size;
        List<MatchListItem> items = from >= listed.size() ? List.of()
                : List.copyOf(listed.subList((int) from, (int) Math.min(from + size, listed.size())));
        return MatchListResponse.of(listed.size(), scope, page, size, today, items);
    }

    /**
     * 거주지가 없으면 범위를 정할 수 없어 요청과 관계없이 전체 지역이다. 거주지가 있으면 요청이 없을 때 내 지역이다.
     */
    private static RegionScope scope(RegionScope requested, MatchSubject subject) {
        if (subject.sidoCode() == null) {
            return RegionScope.ALL;
        }
        return requested == null ? RegionScope.MY_REGION : requested;
    }

    private void requireAccount(Long userId) {
        if (!userAccountRepository.existsById(userId)) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }
    }

    private MatchSubject subject(Long userId, LocalDate today) {
        return MatchSubject.of(userProfileRepository.findById(userId).orElse(null), today);
    }

    private SupportConditions parse(SourceRecord record) {
        return SupportConditions.parse(jsonMapper.readTree(record.getPayload()));
    }
}
