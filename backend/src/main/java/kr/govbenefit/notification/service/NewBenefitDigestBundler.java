package kr.govbenefit.notification.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import kr.govbenefit.notification.repository.NewBenefitTargetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 새 혜택 알림 묶기(REQ-NOTIFY-03, DEC-NOTIFY, 결정 0046). 매일 08:00에 알림을 켠 사용자마다 묶지 않은 항목을 모두 그날 묶음
 * 하나에 넣어 발송 대기로 저장한다. 선정 날짜와 관계없이 묶으므로, 묶기가 돌지 않은 날의 항목은 다음 묶음에 들어간다.
 * 알림을 끈 사용자의 묶지 않은 항목은 묶지 않고 지운다.
 *
 * <p>단일 인스턴스를 전제한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NewBenefitDigestBundler {

    private static final int USER_PAGE_SIZE = 1_000;

    private final NewBenefitTargetRepository targetRepository;
    private final NewBenefitDigestWriter writer;
    private final Clock clock;

    /**
     * 실패해도 예외를 던지지 않는다. 남은 항목은 다음 날 묶는다.
     */
    @Scheduled(cron = "${notification.new-benefit.digest-cron}", zone = "Asia/Seoul")
    public void bundleDaily() {
        try {
            bundleAll();
        } catch (RuntimeException e) {
            log.error("새 혜택 묶기 실패", e);
        }
    }

    public void bundleAll() {
        long start = System.nanoTime();
        LocalDate digestDate = LocalDate.now(clock);
        LocalDateTime now = LocalDateTime.now(clock);
        int deletedTargets = writer.deleteUnbundledOfNotifyOff();
        int digests = 0;
        int skippedUsers = 0;
        Long afterId = 0L;
        while (true) {
            List<Long> userIds = targetRepository.findUnbundledNotifyUserIds(afterId, Limit.of(USER_PAGE_SIZE));
            if (userIds.isEmpty()) {
                break;
            }
            for (Long userId : userIds) {
                if (writer.bundle(userId, digestDate, now)) {
                    digests++;
                } else {
                    skippedUsers++;
                }
            }
            afterId = userIds.getLast();
        }
        log.info("새 혜택 묶기 date={} deletedTargets={} digests={} skippedUsers={} elapsedMs={}", digestDate,
                deletedTargets, digests, skippedUsers, (System.nanoTime() - start) / 1_000_000);
    }
}
