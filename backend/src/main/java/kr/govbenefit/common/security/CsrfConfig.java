package kr.govbenefit.common.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 쿠키로 인증하는 경로에 CSRF 방어를 건다(결정 0017). 로그아웃은 강제 로그아웃의 피해가 작아 넣지 않는다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CsrfProperties.class)
public class CsrfConfig implements WebMvcConfigurer {

    private final CsrfProperties properties;

    CsrfConfig(CsrfProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new CsrfOriginInterceptor(properties)).addPathPatterns(SecurityConfig.REFRESH_PATH);
    }
}
