package kr.govbenefit.account.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Amazon SES 메일 발송 설정(결정 0045). 접근 키, 리전, 발신 주소는 실행 환경의 SES_ACCESS_KEY_ID,
 * SES_SECRET_ACCESS_KEY, SES_REGION, SES_FROM으로만 받는다. {@code auth.mail.sender=ses}일 때만 쓰고, 그때 하나라도
 * 비어 있으면 {@link AccountMailConfig}가 기동을 막는다.
 *
 * @param accessKeyId     발송 권한만 가진 IAM 사용자의 접근 키 ID
 * @param secretAccessKey 그 접근 키의 비밀 키
 * @param region          SES 리전. 예: {@code ap-northeast-2}
 * @param from            발신 주소. 인증한 도메인의 주소여야 하며 이름을 붙일 수 있다
 *                        (예: {@code 정부 혜택 도우미 <help@mail.govbenefit.kr>})
 * @param endpoint        비우면 리전 엔드포인트를 쓴다. 테스트만 로컬 stub 주소를 넣는다
 * @param connectTimeout  연결 시간 제한
 * @param readTimeout     요청을 보낸 뒤 응답을 받을 때까지의 시간 제한
 */
@ConfigurationProperties("auth.mail.ses")
public record SesProperties(String accessKeyId, String secretAccessKey, String region, String from, URI endpoint,
                            Duration connectTimeout, Duration readTimeout) {

    /**
     * 설정을 로그로 남겨도 접근 키가 드러나지 않게 한다.
     */
    @Override
    public String toString() {
        return "SesProperties[accessKeyId=" + mask(accessKeyId) + ", secretAccessKey=" + mask(secretAccessKey)
                + ", region=" + region + ", from=" + from + ", endpoint=" + endpoint + ", connectTimeout="
                + connectTimeout + ", readTimeout=" + readTimeout + "]";
    }

    private static String mask(String value) {
        return value == null || value.isBlank() ? "(없음)" : "****";
    }
}
