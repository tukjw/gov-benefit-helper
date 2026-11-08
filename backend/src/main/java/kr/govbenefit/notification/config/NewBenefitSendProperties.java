package kr.govbenefit.notification.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 새 혜택 알림 발송 설정(결정 0048). 발송 주기 {@code notification.new-benefit.send.cron}은
 * {@code NewBenefitDigestSender}의 {@code @Scheduled}가 읽는다.
 *
 * @param ratePerSecond 초당 SES 호출 수의 상한. SES 계정 한도보다 낮게 두어 계정 메일 몫을 남긴다
 * @param retryDelays   일시 실패 뒤 다시 보내기까지의 간격. 개수가 재시도 횟수다
 * @param siteUrl       메일의 혜택 상세 링크와 알림 끄기 안내에 쓰는 웹 주소. 끝에 {@code /}가 없다
 */
@ConfigurationProperties("notification.new-benefit.send")
public record NewBenefitSendProperties(int ratePerSecond, List<Duration> retryDelays, String siteUrl) {
}
