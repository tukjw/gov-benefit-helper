package kr.govbenefit.match.service;

import java.time.Clock;
import java.time.LocalDate;
import kr.govbenefit.account.repository.UserAccountRepository;
import kr.govbenefit.benefit.domain.Benefit;
import kr.govbenefit.benefit.repository.BenefitRepository;
import kr.govbenefit.collection.domain.SourceEndpoint;
import kr.govbenefit.collection.domain.SourceRecord;
import kr.govbenefit.collection.repository.SourceRecordRepository;
import kr.govbenefit.common.api.BusinessException;
import kr.govbenefit.common.api.ErrorCode;
import kr.govbenefit.match.domain.ConditionMatcher;
import kr.govbenefit.match.domain.MatchSubject;
import kr.govbenefit.match.domain.SupportConditions;
import kr.govbenefit.match.dto.MatchResponse;
import kr.govbenefit.profile.repository.UserProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * 토큰 주인의 프로필과 혜택 하나의 지원조건 비교(REQ-MATCH-01, DEC-MATCH). 사용자는 토큰으로만 정한다(결정 0018).
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
    private final JsonMapper jsonMapper;
    private final Clock clock;

    /**
     * 프로필·혜택·지원조건을 한 읽기 트랜잭션에서 같은 스냅샷으로 읽는다. 프로필을 저장한 적이 없으면 모든 값이 미입력이다.
     *
     * @throws BusinessException 계정이 없다({@code UNAUTHENTICATED}). 수집되지 않은 서비스 ID다({@code NOT_FOUND}).
     */
    public MatchResponse match(Long userId, String serviceId) {
        requireAccount(userId);
        Benefit benefit = benefitRepository.findByServiceId(serviceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "혜택이 없습니다. serviceId=" + serviceId));
        LocalDate today = LocalDate.now(clock);
        MatchSubject subject = subject(userId, today);
        SupportConditions conditions = sourceRecordRepository
                .findByServiceIdAndEndpoint(serviceId, SourceEndpoint.SUPPORT_CONDITIONS)
                .map(this::parse)
                .orElse(null);
        return MatchResponse.of(benefit, conditions, subject,
                ConditionMatcher.match(subject, conditions, benefit.getUserType()), today);
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
