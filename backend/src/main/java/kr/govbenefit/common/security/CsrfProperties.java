package kr.govbenefit.common.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 쿠키로 인증하는 요청의 CSRF 방어 설정(결정 0017).
 *
 * @param allowedOrigins 요청에 {@code Origin} 헤더가 있을 때 허용하는 출처. 예: {@code https://govbenefit.kr}
 */
@ConfigurationProperties("auth.csrf")
public record CsrfProperties(List<String> allowedOrigins) {

    public CsrfProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
    }
}
