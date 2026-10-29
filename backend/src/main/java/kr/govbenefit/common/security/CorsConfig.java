package kr.govbenefit.common.security;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * 프론트가 브라우저에서 API를 직접 부르므로 프론트 출처의 요청을 허용한다(결정 0029). 허용 출처는 CSRF 방어와 같은 목록을 써서 두 목록이
 * 어긋나지 않게 한다. 허용하지 않은 출처의 요청과 사전 요청은 403이다.
 *
 * <p>리프레시 토큰 쿠키를 주고받도록 쿠키 전송을 허용한다(결정 0029). 출처를 목록으로 지정하므로 함께 쓸 수 있다. 발송 제한의
 * {@code Retry-After}는 CORS 기본 노출 헤더가 아니라서 노출 목록에 넣는다.
 */
@Configuration(proxyBeanMethods = false)
public class CorsConfig {

    @Bean
    CorsConfigurationSource corsConfigurationSource(CsrfProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.allowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE"));
        configuration.setAllowedHeaders(List.of(
                HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE, CsrfOriginInterceptor.REQUIRED_HEADER));
        configuration.setAllowCredentials(true);
        configuration.setExposedHeaders(List.of(HttpHeaders.RETRY_AFTER));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }
}
