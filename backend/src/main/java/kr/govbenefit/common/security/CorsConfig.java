package kr.govbenefit.common.security;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * 프론트가 브라우저에서 API를 직접 부르므로 프론트 출처의 요청을 허용한다(결정 0027). 허용 출처는 CSRF 방어와 같은 목록을 써서 두 목록이
 * 어긋나지 않게 한다. 허용하지 않은 출처의 요청과 사전 요청은 403이다. 쿠키 전송은 아직 허용하지 않는다(#73에서 정한다).
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

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }
}
