package kr.govbenefit.notification.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import kr.govbenefit.notification.domain.NewBenefitDigest;
import kr.govbenefit.notification.repository.NewBenefitDigestRepository;
import kr.govbenefit.notification.repository.NewBenefitTargetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자 하나의 묶음과 항목 연결을 한 트랜잭션으로 저장한다. {@link NewBenefitDigestBundler}만 부른다.
 * 묶기가 도중에 멈춰도 사용자마다 묶음과 항목 연결이 함께 있거나 함께 없고, 묶지 않은 항목은 다음 묶기에서 묶는다.
 */
@Component
@RequiredArgsConstructor
public class NewBenefitDigestWriter {

    private final NewBenefitDigestRepository digestRepository;
    private final NewBenefitTargetRepository targetRepository;

    /**
     * @return 지운 항목 수
     */
    @Transactional
    public int deleteUnbundledOfNotifyOff() {
        return targetRepository.deleteUnbundledOfNotifyOff();
    }

    /**
     * 사용자의 묶지 않은 항목을 모두 그날 묶음 하나에 넣는다.
     *
     * @return 묶음을 만들었으면 {@code true}. 그날 묶음이 이미 있으면 만들지 않고, 남은 항목은 다음 날 묶음에 들어간다.
     */
    @Transactional
    public boolean bundle(Long userId, LocalDate digestDate, LocalDateTime now) {
        if (digestRepository.existsByUserIdAndDigestDate(userId, digestDate)) {
            return false;
        }
        NewBenefitDigest digest = digestRepository.save(NewBenefitDigest.pending(userId, digestDate, now));
        targetRepository.assignDigest(userId, digest.getId());
        return true;
    }
}
