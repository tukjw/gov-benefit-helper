package kr.govbenefit.common.security;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

/**
 * 액세스 토큰을 발급한다(결정 0014). {@code sub}는 {@code user_account.id}다.
 */
@Component
@RequiredArgsConstructor
public class AccessTokenIssuer {

    /** 토큰 용도. 이후 다른 용도의 JWT와 섞이지 않게 검증한다. */
    static final String TOKEN_USE_CLAIM = "token_use";
    static final String TOKEN_USE_ACCESS = "access";

    private final JwtEncoder jwtEncoder;
    private final JwtProperties properties;
    private final Clock clock;

    public AccessToken issue(Long userId) {
        return issue(userId, clock.instant());
    }

    /**
     * 발급 시각을 받아 발급한다. 테스트에서 지난 시각의 토큰을 만들 때 쓴다. JWT 시각은 초 단위이므로
     * 응답의 만료 시각이 토큰의 {@code exp}와 같도록 발급 시각을 초로 자른다.
     */
    public AccessToken issue(Long userId, Instant issuedAt) {
        Instant iat = issuedAt.truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = iat.plus(properties.accessTokenTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(String.valueOf(userId))
                .issuedAt(iat)
                .expiresAt(expiresAt)
                .claim(TOKEN_USE_CLAIM, TOKEN_USE_ACCESS)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
        String value = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new AccessToken(value, expiresAt);
    }

    /**
     * @param value     서명한 JWT
     * @param expiresAt 만료 시각
     */
    public record AccessToken(String value, Instant expiresAt) {
    }
}
