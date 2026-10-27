package kr.govbenefit.account.config;

import kr.govbenefit.account.mail.AccountMailSender;
import kr.govbenefit.account.mail.LoggingAccountMailSender;
import kr.govbenefit.account.mail.ResendAccountMailSender;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 메일 발송 구현을 {@code auth.mail.sender}로 고른다(결정 0032).
 * 지정하지 않으면 {@link AccountMailSender} 빈이 없어 기동에 실패한다. 설정 실수 하나로 운영에서 인증번호가 로그로
 * 새거나 메일 없이 뜨지 않게 하기 위해서다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ResendProperties.class)
public class AccountMailConfig {

    /**
     * 메일을 보내지 않고 인증번호와 재설정 링크를 로그로 남긴다. local 프로필에서만 켠다.
     */
    @Bean
    @ConditionalOnProperty(name = "auth.mail.sender", havingValue = "logging")
    AccountMailSender loggingAccountMailSender() {
        return new LoggingAccountMailSender();
    }

    /**
     * Resend로 보낸다. API 키나 발신 주소가 없으면 기동에 실패한다.
     */
    @Bean
    @ConditionalOnProperty(name = "auth.mail.sender", havingValue = "resend")
    AccountMailSender resendAccountMailSender(ResendProperties properties) {
        if (properties.apiKey() == null || properties.apiKey().isBlank()) {
            throw new IllegalStateException("Resend API 키(RESEND_API_KEY)가 설정되지 않았습니다.");
        }
        if (properties.from() == null || properties.from().isBlank()) {
            throw new IllegalStateException("메일 발신 주소(RESEND_FROM)가 설정되지 않았습니다.");
        }
        return new ResendAccountMailSender(properties);
    }
}
