package kr.govbenefit.notification.service;

import java.time.LocalDateTime;
import java.util.List;
import kr.govbenefit.collection.repository.NewBenefitRepository;
import kr.govbenefit.notification.repository.NewBenefitTargetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 새 혜택 하나의 알림 대상과 선정 시각을 한 트랜잭션으로 저장한다. {@link NewBenefitTargetSelector}만 부른다.
 * 선정이 도중에 멈춰도 혜택마다 대상이 전부 있거나 하나도 없고, 선정 시각이 없는 혜택은 다음 선정에서 다시 고른다.
 */
@Component
@RequiredArgsConstructor
public class NewBenefitTargetWriter {

    private final NewBenefitTargetRepository targetRepository;
    private final NewBenefitRepository newBenefitRepository;

    /**
     * @param userIds 대상 사용자. 비어 있어도 선정 시각을 남긴다.
     */
    @Transactional
    public void save(Long benefitId, List<Long> userIds, LocalDateTime now) {
        userIds.forEach(userId -> targetRepository.insert(userId, benefitId));
        newBenefitRepository.findById(benefitId).orElseThrow().markTargetsSelected(now);
    }
}
