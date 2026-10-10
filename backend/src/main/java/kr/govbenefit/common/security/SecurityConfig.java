package kr.govbenefit.common.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * API 인증 경계(결정 0015). 허용 목록에 없는 경로는 유효한 액세스 토큰이 있어야 한다. 새 공개 API는 이 목록에 추가한다.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    /** 로그인 없이 쓰는 경로. 메서드와 관계없이 허용해 잘못된 메서드는 MVC가 405로 응답하게 한다. */
    private static final String[] PUBLIC_PATHS = {
            "/api/benefits/**",
            "/api/auth/**",
            "/error",
            // 문서는 local 프로필에서만 열린다. 그 밖에서는 처리할 API가 없어 404다(결정 0012).
            "/v3/api-docs/**",
            "/swagger-ui.html",
            "/swagger-ui/**"};

    /**
     * 인증은 Authorization 헤더로만 받고 세션·쿠키를 쓰지 않는다. 브라우저가 자동으로 붙이는 자격 증명이 없으므로 CSRF 보호를 끈다.
     * 리프레시 토큰 쿠키를 도입할 때 재발급 경로에 CSRF 방어를 넣는다(DEC-AUTH).
     */
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ApiAuthenticationEntryPoint entryPoint) throws Exception {
        return http
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> { })
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
     * BCrypt로 인코딩하고 저장값에 {@code {bcrypt}} 접두사를 붙인다(결정 0013).
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
