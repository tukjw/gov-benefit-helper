package kr.govbenefit.account.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 비밀번호 재설정 설정(결정 0019).
 *
 * @param tokenTtl 재설정 토큰 유효 기간
 * @param url      프론트의 재설정 화면. 메일의 링크는 {@code url#token=<토큰>}이다.
 */
@ConfigurationProperties("auth.password-reset")
public record PasswordResetProperties(Duration tokenTtl, String url) {

    /**
     * 토큰을 fragment에 둔다. 브라우저가 fragment를 서버로 보내지 않아 프론트 접근 로그와 Referer에 남지 않는다.
     */
    public String linkFor(String token) {
        return url + "#token=" + token;
    }
}
