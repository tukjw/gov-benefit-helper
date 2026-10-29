package kr.govbenefit.common.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 액세스 토큰 설정(결정 0014). 서명 키는 실행 환경의 JWT_SECRET으로만 받는다.
 *
 * @param secret                  base64로 인코딩한 HS256 키. 32바이트 이상이어야 한다.
 * @param issuer                  발급자({@code iss}). 검증할 때도 같은 값이어야 한다.
 * @param accessTokenTtl          액세스 토큰 유효 기간
 * @param generateSecretIfMissing 키가 없으면 기동할 때 임의 키를 만든다. local과 테스트에서만 켠다.
 */
@ConfigurationProperties("auth.jwt")
public record JwtProperties(String secret, String issuer, Duration accessTokenTtl, boolean generateSecretIfMissing) {

    public boolean hasSecret() {
        return secret != null && !secret.isBlank();
    }

    /**
     * 설정을 로그로 남겨도 키가 드러나지 않게 한다.
     */
    @Override
    public String toString() {
        return "JwtProperties[secret=" + (hasSecret() ? "****" : "(없음)") + ", issuer=" + issuer
                + ", accessTokenTtl=" + accessTokenTtl + ", generateSecretIfMissing=" + generateSecretIfMissing + "]";
    }
}
