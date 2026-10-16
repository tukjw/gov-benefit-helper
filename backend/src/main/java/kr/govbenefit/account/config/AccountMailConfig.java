package kr.govbenefit.account.config;

import kr.govbenefit.account.mail.AccountMailSender;
import kr.govbenefit.account.mail.LoggingAccountMailSender;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 메일 발송 구현을 {@code auth.mail.sender}로 고른다(결정 0020). 지정하지 않으면 {@link AccountMailSender} 빈이 없어
 * 기동에 실패한다. 실제 발송 구현이 들어올 때까지 운영 설정에서 인증번호가 로그로 새지 않게 하기 위해서다.
 */
@Configuration(proxyBeanMethods = false)
public class AccountMailConfig {

    /**
     * 메일을 보내지 않고 인증번호와 재설정 링크를 로그로 남긴다. local 프로필에서만 켠다.
     */
    @Bean
    @ConditionalOnProperty(name = "auth.mail.sender", havingValue = "logging")
    AccountMailSender loggingAccountMailSender() {
        return new LoggingAccountMailSender();
    }
}
