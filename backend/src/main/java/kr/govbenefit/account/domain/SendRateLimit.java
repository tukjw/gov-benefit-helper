package kr.govbenefit.account.domain;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 인증·재설정 메일의 발송 제한(결정 0019). 같은 대상에게 마지막 발송에서 {@code cooldown}이 지나야 다시 보내고,
 * 최근 {@code window} 안에는 {@code maxPerWindow}번까지 보낸다. 창은 고정된 날짜가 아니라 지금부터 거꾸로 센다.
 *
 * @param cooldown     같은 대상에게 다시 보내기까지의 간격
 * @param window       발송 횟수를 세는 기간
 * @param maxPerWindow 기간 안의 최대 발송 횟수
 */
public record SendRateLimit(Duration cooldown, Duration window, int maxPerWindow) {

    /**
     * 창의 시작. 발송 시각이 이 값보다 뒤면 창 안이다.
     */
    public LocalDateTime windowStart(LocalDateTime now) {
        return now.minus(window);
    }

    /**
     * @param lastSentAt      마지막 발송 시각. 보낸 적이 없으면 비어 있다.
     * @param sentInWindow    창 안의 발송 횟수
     * @param oldestInWindow  창 안에서 가장 오래된 발송 시각
     * @return 다시 보낼 수 있을 때까지 남은 시간. 지금 보낼 수 있으면 비어 있다.
     */
    public Optional<Duration> retryAfter(LocalDateTime now, Optional<LocalDateTime> lastSentAt, long sentInWindow,
                                         Optional<LocalDateTime> oldestInWindow) {
        Duration wait = Duration.ZERO;
        if (lastSentAt.isPresent()) {
            wait = max(wait, Duration.between(now, lastSentAt.get().plus(cooldown)));
        }
        if (sentInWindow >= maxPerWindow && oldestInWindow.isPresent()) {
            wait = max(wait, Duration.between(now, oldestInWindow.get().plus(window)));
        }
        return wait.isPositive() ? Optional.of(wait) : Optional.empty();
    }

    private static Duration max(Duration a, Duration b) {
        return a.compareTo(b) >= 0 ? a : b;
    }
}
