package kr.govbenefit.common.security;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtTypeValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * 액세스 토큰의 서명 키와 발급·검증기(결정 0014).
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JwtProperties.class)
public class JwtConfig {

    /** HS256 키의 최소 길이(바이트) */
    static final int MIN_SECRET_BYTES = 32;

    @Bean
    SecretKey jwtSecretKey(JwtProperties properties) {
        return new SecretKeySpec(secretBytes(properties), "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
        return NimbusJwtEncoder.withSecretKey(jwtSecretKey).algorithm(MacAlgorithm.HS256).build();
    }

    /**
     * 서명(HS256만), 만료(시계 오차 없음), 발급자, 토큰 용도를 검증한다. 만료 판정은 {@link Clock} 빈을 따른다.
     */
    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtSecretKey, JwtProperties properties, Clock clock) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSecretKey).macAlgorithm(MacAlgorithm.HS256).build();
        JwtTimestampValidator timestamp = new JwtTimestampValidator(Duration.ZERO);
        timestamp.setClock(clock);
        timestamp.setAllowEmptyExpiryClaim(false);
        List<OAuth2TokenValidator<Jwt>> validators = List.of(
                JwtTypeValidator.jwt(),
                timestamp,
                new JwtIssuerValidator(properties.issuer()),
                new JwtClaimValidator<String>(AccessTokenIssuer.TOKEN_USE_CLAIM, AccessTokenIssuer.TOKEN_USE_ACCESS::equals));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
        return decoder;
    }

    static byte[] secretBytes(JwtProperties properties) {
        if (!properties.hasSecret()) {
            if (!properties.generateSecretIfMissing()) {
                throw new IllegalStateException("액세스 토큰 서명 키(JWT_SECRET)가 설정되지 않았습니다.");
            }
            log.warn("JWT_SECRET이 없어 임의 서명 키를 만들었습니다. 재시작하면 발급한 토큰이 모두 무효가 됩니다.");
            byte[] generated = new byte[MIN_SECRET_BYTES];
            new SecureRandom().nextBytes(generated);
            return generated;
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(properties.secret().strip());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("JWT_SECRET은 base64로 인코딩한 값이어야 합니다.");
        }
        if (decoded.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("JWT_SECRET은 " + MIN_SECRET_BYTES + "바이트 이상이어야 합니다.");
        }
        return decoded;
    }
}
