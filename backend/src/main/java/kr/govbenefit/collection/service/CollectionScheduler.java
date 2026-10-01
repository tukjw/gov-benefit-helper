package kr.govbenefit.collection.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 하루 1회 전체 수집. 수집을 실행하는 HTTP 경로는 두지 않으며, 이 스케줄러와 {@code collect} 프로필 실행기만 수집을 시작한다.
 * 단일 인스턴스를 전제한다. 실행이 겹칠 때의 처리는 아직 없다.
 */
@Slf4j
@Component
@ConditionalOnBooleanProperty("gov24.schedule.enabled")
@RequiredArgsConstructor
public class CollectionScheduler {

    private final CollectionService collectionService;

    @Scheduled(cron = "${gov24.schedule.cron}", zone = "Asia/Seoul")
    public void collectDaily() {
        try {
            collectionService.collectAll();
        } catch (IllegalStateException e) {
            // 인증키가 없는 환경. 실행 기록을 만들지 않고 넘어간다.
            log.warn("정기 수집을 건너뜀: {}", e.getMessage());
        }
    }
}
