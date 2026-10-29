package kr.govbenefit.account.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({RefreshTokenProperties.class, EmailVerificationProperties.class,
        PasswordResetProperties.class, AccountMailProperties.class, LoginAttemptProperties.class})
public class AccountConfig {
}
