package kr.govbenefit.account.config;

import java.time.Duration;
import kr.govbenefit.account.domain.SendRateLimit;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 인증·재설정 메일 발송 제한(결정 0019). 발송 구현을 고르는 {@code auth.mail.sender}는 {@link AccountMailConfig}가 읽는다.
 *
 * @param cooldown     같은 이메일에 다시 보내기까지의 간격
 * @param window       발송 횟수를 세는 기간
 * @param maxPerWindow 기간 안의 최대 발송 횟수
 */
@ConfigurationProperties("auth.mail")
public record AccountMailProperties(Duration cooldown, Duration window, int maxPerWindow) {

    public SendRateLimit toSendRateLimit() {
        return new SendRateLimit(cooldown, window, maxPerWindow);
    }
}
