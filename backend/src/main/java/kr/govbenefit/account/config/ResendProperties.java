package kr.govbenefit.account.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Resend 메일 발송 설정(결정 0032). API 키와 발신 주소는 실행 환경의 RESEND_API_KEY, RESEND_FROM으로만 받는다.
 * {@code auth.mail.sender=resend}일 때만 쓰고, 그때 두 값이 없으면 {@link AccountMailConfig}가 기동을 막는다.
 *
 * @param from           발신 주소. 이름을 붙일 수 있다(예: {@code 정부 혜택 도우미 <no-reply@govbenefit.kr>})
 * @param connectTimeout 연결 시간 제한
 * @param readTimeout    요청을 보낸 뒤 응답 헤더를 받을 때까지의 시간 제한
 */
@ConfigurationProperties("auth.mail.resend")
public record ResendProperties(URI baseUrl, String apiKey, String from, Duration connectTimeout,
                               Duration readTimeout) {

    /**
     * 설정을 로그로 남겨도 API 키가 드러나지 않게 한다.
     */
    @Override
    public String toString() {
        return "ResendProperties[baseUrl=" + baseUrl + ", apiKey=" + (apiKey == null || apiKey.isBlank()
                ? "(없음)" : "****") + ", from=" + from + ", connectTimeout=" + connectTimeout
                + ", readTimeout=" + readTimeout + "]";
    }
}
