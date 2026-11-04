package kr.govbenefit.notification.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import kr.govbenefit.account.repository.UserAccountRepository;
import kr.govbenefit.collection.domain.CollectionRunFinished;
import kr.govbenefit.collection.domain.NewBenefit;
import kr.govbenefit.collection.domain.NewBenefitStatus;
import kr.govbenefit.collection.repository.NewBenefitRepository;
import kr.govbenefit.match.domain.ConditionMatcher;
import kr.govbenefit.match.domain.MatchResult;
import kr.govbenefit.match.domain.MatchSubject;
import kr.govbenefit.match.service.MatchCandidate;
import kr.govbenefit.match.service.MatchCandidateLoader;
import kr.govbenefit.profile.domain.UserProfile;
import kr.govbenefit.profile.repository.UserProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;

/**
 * 새 혜택 알림 대상 선정(REQ-NOTIFY-03, DEC-NOTIFY). 판단 가능(READY)인 새 혜택마다, 알림을 켠 사용자 중 조건 비교 결과가
 * 해당인 사용자를 고른다. 판정은 조건 비교와 같은 입력({@link MatchCandidate}, {@link MatchSubject})과 함수를 쓴다.
 * 혜택마다 한 번만 고르므로, 고른 뒤 프로필을 바꿔 새로 해당하게 된 사용자는 대상이 아니다.
 * 판정은 트랜잭션 밖에서 하고, 저장만 혜택마다 {@link NewBenefitTargetWriter}가 한 트랜잭션으로 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NewBenefitTargetSelector {

    private static final int USER_PAGE_SIZE = 1_000;

    private final NewBenefitRepository newBenefitRepository;
    private final UserAccountRepository userAccountRepository;
    private final UserProfileRepository userProfileRepository;
    private final MatchCandidateLoader candidateLoader;
    private final NewBenefitTargetWriter writer;
    private final Clock clock;

    /**
     * 수집 실행이 끝나면 상태와 관계없이 고른다. 이전 실행에서 남은 혜택도 이때 고른다.
     * 실패해도 예외를 던지지 않는다. 같은 이벤트를 받는 조건 비교 목록 교체를 막지 않기 위해서다. 남은 혜택은 다음 수집 뒤에 고른다.
     */
    @EventListener
    public void onCollectionRunFinished(CollectionRunFinished event) {
        try {
            selectAll();
        } catch (RuntimeException e) {
            log.error("새 혜택 대상 선정 실패 runId={}", event.runId(), e);
        }
    }

    /**
     * 대상을 아직 고르지 않은 판단 가능 새 혜택을 모두 고른다.
     */
    public void selectAll() {
        LocalDate today = LocalDate.now(clock);
        LocalDateTime now = LocalDateTime.now(clock);
        for (NewBenefit newBenefit : newBenefitRepository
                .findAllByStatusAndTargetsSelectedAtIsNullOrderByBenefitId(NewBenefitStatus.READY)) {
            select(newBenefit.getBenefitId(), today, now);
        }
    }

    private void select(Long benefitId, LocalDate today, LocalDateTime now) {
        long start = System.nanoTime();
        MatchCandidate candidate = candidateLoader.load(benefitId);
        List<Long> targets = new ArrayList<>();
        int users = 0;
        Long afterId = 0L;
        while (true) {
            List<Long> userIds = userAccountRepository.findNewBenefitNotifyIds(afterId, Limit.of(USER_PAGE_SIZE));
            if (userIds.isEmpty()) {
                break;
            }
            Map<Long, UserProfile> profiles = userProfileRepository.findAllWithConditionCodesByUserIdIn(userIds)
                    .stream().collect(Collectors.toMap(UserProfile::getUserId, Function.identity()));
            for (Long userId : userIds) {
                // 프로필이 없으면 모든 값이 미입력이다. 조건 비교와 같다.
                MatchSubject subject = MatchSubject.of(profiles.get(userId), today);
                MatchResult result = ConditionMatcher.match(subject, candidate.conditions(), candidate.userType(),
                        candidate.agencyRegion()).result();
                if (result == MatchResult.MATCH) {
                    targets.add(userId);
                }
            }
            users += userIds.size();
            afterId = userIds.getLast();
        }
        writer.save(benefitId, targets, now);
        log.info("새 혜택 대상 선정 benefitId={} users={} targets={} elapsedMs={}", benefitId, users, targets.size(),
                (System.nanoTime() - start) / 1_000_000);
    }
}
