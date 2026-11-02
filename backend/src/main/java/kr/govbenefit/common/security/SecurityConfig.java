package kr.govbenefit.common.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * API 인증 경계(결정 0018). 허용 목록에 없는 경로는 유효한 액세스 토큰이 있어야 한다. 새 공개 API는 이 목록에 추가한다.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    /** 리프레시 토큰 쿠키로 인증하는 경로(결정 0017) */
    static final String REFRESH_PATH = "/api/auth/refresh";
    static final String LOGOUT_PATH = "/api/auth/logout";

    /** 로그인 없이 쓰는 경로. 메서드와 관계없이 허용해 잘못된 메서드는 MVC가 405로 응답하게 한다. */
    private static final String[] PUBLIC_PATHS = {
            "/api/benefits/**",
            "/api/auth/**",
            // 요청에 담은 프로필 값으로 비교하고 저장하지 않는다(DEC-AUTH 공개 범위).
            "/api/matches/**",
            // 비회원 조건 비교의 입력 선택지
            "/api/profile/options",
            // 관리 포트에서만 열린다(결정 0037). 배포 스크립트가 기동 완료를, Prometheus가 지표를 읽는다.
            // 앱 포트에서는 처리할 경로가 없어 404다.
            "/actuator/health",
            "/actuator/prometheus",
            "/error",
            // 문서는 local 프로필에서만 열린다. 그 밖에서는 처리할 API가 없어 404다(결정 0012).
            "/v3/api-docs/**",
            "/swagger-ui.html",
            "/swagger-ui/**"};

    /**
     * 개인 API의 인증은 Authorization 헤더로만 받고 세션을 쓰지 않는다. 쿠키는 재발급·로그아웃의 리프레시 토큰뿐이며, 재발급의
     * CSRF 방어는 {@link CsrfOriginInterceptor}가 한다(결정 0017). 그래서 Spring Security의 CSRF 필터는 끈다.
     */
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ApiAuthenticationEntryPoint entryPoint) throws Exception {
        return http
                // 허용 출처는 CorsConfig의 CorsConfigurationSource가 정한다.
                .cors(Customizer.withDefaults())
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> { })
                        .bearerTokenResolver(bearerTokenResolver())
                        .authenticationEntryPoint(entryPoint))
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(entryPoint))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .build();
    }

    /**
     * 재발급·로그아웃은 리프레시 토큰 쿠키로만 판단하고 Authorization 헤더를 읽지 않는다. 액세스 토큰이 만료되어 재발급을 부를 때
     * 클라이언트가 만료된 토큰을 그대로 붙여 와도 401이 되지 않게 한다. 그 밖의 경로는 보낸 토큰을 항상 검증한다(결정 0018).
     */
    static BearerTokenResolver bearerTokenResolver() {
        DefaultBearerTokenResolver delegate = new DefaultBearerTokenResolver();
        RequestMatcher cookieOnly = new OrRequestMatcher(
                PathPatternRequestMatcher.pathPattern(HttpMethod.POST, REFRESH_PATH),
                PathPatternRequestMatcher.pathPattern(HttpMethod.POST, LOGOUT_PATH));
        return request -> cookieOnly.matches(request) ? null : delegate.resolve(request);
    }

    /**
     * BCrypt로 인코딩하고 저장값에 {@code {bcrypt}} 접두사를 붙인다(결정 0013).
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
