package kr.govbenefit.account.config;

import static org.assertj.core.api.Assertions.assertThat;

import kr.govbenefit.account.mail.AccountMailSender;
import kr.govbenefit.account.mail.LoggingAccountMailSender;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 발송 구현은 설정으로만 고른다(결정 0020). 기본 설정에는 구현이 없어 실제 메일도 로그도 나가지 않는다.
 */
class AccountMailConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AccountMailConfig.class);

    @Test
    void 지정하지_않으면_발송_구현이_없다() {
        runner.run(context -> assertThat(context).doesNotHaveBean(AccountMailSender.class));
    }

    @Test
    void logging이면_로그_구현을_쓴다() {
        runner.withPropertyValues("auth.mail.sender=logging")
                .run(context -> assertThat(context).getBean(AccountMailSender.class)
                        .isInstanceOf(LoggingAccountMailSender.class));
    }
}
